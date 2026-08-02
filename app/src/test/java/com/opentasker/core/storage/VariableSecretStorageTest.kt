package com.opentasker.core.storage

import com.opentasker.core.model.Variable
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableSecretStorageTest {
    @Test
    fun aesGcmEnvelopeAuthenticatesValueAndVariableName() {
        val codec = codec(newKey())
        val envelope = codec.encrypt("API_TOKEN", "super-secret-value")

        assertTrue(envelope.startsWith("otsec:v1:"))
        assertFalse(envelope.contains("super-secret-value"))
        assertEquals("super-secret-value", codec.decrypt("API_TOKEN", envelope).getOrThrow())
        assertTrue(codec.decrypt("OTHER_TOKEN", envelope).isFailure)

        val tamperIndex = envelope.lastIndex - 2
        val tampered = envelope.replaceRange(
            tamperIndex,
            tamperIndex + 1,
            if (envelope[tamperIndex] == 'A') "B" else "A",
        )
        assertTrue(codec.decrypt("API_TOKEN", tampered).isFailure)
    }

    @Test
    fun secretRowsStoreOnlyCiphertextAndDecodeForUi() = runBlocking {
        val dao = FakeVariableDao()
        val repository = VariableRepository(dao, codec(newKey()))

        repository.upsert(Variable("API_TOKEN", "token-123", isSecret = true))

        val stored = dao.get(0, "API_TOKEN")!!
        assertTrue(stored.isSecret)
        assertNotEquals("token-123", stored.value)
        assertFalse(stored.value.contains("token-123"))
        assertEquals("token-123", repository.observeGlobals().first().single().value)
    }

    /**
     * The fork stores a variable name **verbatim** — it strips only a leading `%` sigil and trims.
     * Upstream canonicalised case to encode scope; here scope is derived from the name's first
     * character at read time, and names may be Japanese, so rewriting or ASCII-validating them at the
     * storage boundary would corrupt them. Only an empty name is refused.
     */
    @Test
    fun storageStripsOnlyTheSigilAndKeepsTheNameVerbatim() = runBlocking {
        val dao = FakeVariableDao()
        val repository = VariableRepository(dao, codec(newKey()))

        repository.upsert(Variable("%counter", "7"))
        repository.importVariable(Variable("myValue", "8"))
        repository.upsert(Variable("  %電池  ", "満"))

        assertEquals("7", dao.get(0, "counter")?.value)
        assertEquals("8", dao.get(0, "myValue")?.value)
        assertEquals("満", dao.get(0, "電池")?.value)
        assertTrue(runCatching { repository.upsert(Variable("%", "nameless")) }.isFailure)
    }

    @Test
    fun projectScopedAndSuperGlobalOfTheSameNameAreDistinctRows() = runBlocking {
        val dao = FakeVariableDao()
        val repository = VariableRepository(dao, codec(newKey()))

        repository.upsert(Variable("DT_Ampmn", "super"))
        repository.upsert(Variable("DT_Ampmn", "project", projectId = 12))

        assertEquals("super", dao.get(0, "DT_Ampmn")?.value)
        assertEquals("project", dao.get(12, "DT_Ampmn")?.value)
        assertEquals(2, dao.getAll().size)
    }

    @Test
    fun concurrentDisjointGlobalCommitsMergeWithoutLoss() = runBlocking {
        val dao = FakeVariableDao(
            VariableEntity(projectId = 0, name = "ALPHA", value = "0"),
            VariableEntity(projectId = 0, name = "BETA", value = "0"),
        )
        val key = newKey()
        val first = VariableRepository(dao, codec(key))
        val second = VariableRepository(dao, codec(key))
        val baseline = first.runtimeGlobals()

        val results = listOf(
            async {
                first.persistRuntimeAtomically(
                    baseline,
                    listOf(RuntimeVariableValue("ALPHA", "1", isSecret = false)),
                )
            },
            async {
                second.persistRuntimeAtomically(
                    baseline,
                    listOf(RuntimeVariableValue("BETA", "2", isSecret = false)),
                )
            },
        ).awaitAll()

        assertTrue(results.all { it.conflictedNames.isEmpty() })
        assertEquals(mapOf("ALPHA" to "1", "BETA" to "2"), first.runtimeGlobals().values)
    }

    @Test
    fun concurrentSameGlobalCommitKeepsFirstCommittedValue() = runBlocking {
        val dao = FakeVariableDao(VariableEntity(projectId = 0, name = "COUNT", value = "0"))
        val key = newKey()
        val first = VariableRepository(dao, codec(key))
        val second = VariableRepository(dao, codec(key))
        val baseline = first.runtimeGlobals()

        val results = listOf(
            async {
                first.persistRuntimeAtomically(
                    baseline,
                    listOf(RuntimeVariableValue("COUNT", "1", isSecret = false)),
                )
            },
            async {
                second.persistRuntimeAtomically(
                    baseline,
                    listOf(RuntimeVariableValue("COUNT", "2", isSecret = false)),
                )
            },
        ).awaitAll()

        assertEquals(1, results.count { "COUNT" in it.appliedNames })
        assertEquals(1, results.count { "COUNT" in it.conflictedNames })
        assertTrue(first.runtimeGlobals().values["COUNT"] in setOf("1", "2"))
    }

    @Test
    fun missingOrReplacedKeystoreKeyRequiresReentryWithoutReturningCiphertext() = runBlocking {
        val dao = FakeVariableDao()
        VariableRepository(dao, codec(newKey())).upsert(
            Variable("API_TOKEN", "token-123", isSecret = true),
        )

        val restoredOnDifferentDevice = VariableRepository(dao, codec(newKey()))
        val variable = restoredOnDifferentDevice.observeGlobals().first().single()
        val runtime = restoredOnDifferentDevice.runtimeGlobals()

        assertTrue(variable.isSecret)
        assertFalse(variable.secretAvailable)
        assertEquals("", variable.value)
        assertFalse("Unavailable secrets must not be expanded", runtime.values.containsKey("API_TOKEN"))
        assertEquals(setOf("API_TOKEN"), runtime.unavailableSecretNames)

        restoredOnDifferentDevice.upsert(variable.copy(value = "replacement", secretAvailable = true))
        assertEquals("replacement", restoredOnDifferentDevice.runtimeGlobals().values["API_TOKEN"])
    }

    @Test
    fun legacyNameMaskedVariablesAreEncryptedBeforeReadOrExport() = runBlocking {
        val dao = FakeVariableDao(
            VariableEntity(projectId = 0, name = "PASSWORD", value = "legacy-plaintext", isSecret = true),
            VariableEntity(projectId = 0, name = "COUNT", value = "7"),
        )
        val repository = VariableRepository(dao, codec(newKey()))

        val observed = repository.observeGlobals().first()
        val storedSecret = dao.get(0, "PASSWORD")!!
        val exported = repository.ordinaryExport()

        assertTrue(storedSecret.isSecret)
        assertFalse(storedSecret.value.contains("legacy-plaintext"))
        assertTrue(observed.single { it.name == "PASSWORD" }.isSecret)
        assertEquals(listOf("COUNT"), exported.variables.map { it.name })
        assertEquals(1, exported.omittedSecretCount)
    }

    @Test
    fun legacyValueThatOnlyLooksLikeAnEnvelopeIsStillEncrypted() = runBlocking {
        val legacyValue = "otsec:v1:not-a-valid-envelope"
        val dao = FakeVariableDao(
            VariableEntity(projectId = 0, name = "API_TOKEN", value = legacyValue, isSecret = true),
        )
        val repository = VariableRepository(dao, codec(newKey()))

        repository.requireEncryptedSecretRows()

        assertNotEquals(legacyValue, dao.get(0, "API_TOKEN")!!.value)
        assertEquals(legacyValue, repository.runtimeGlobals().values["API_TOKEN"])
    }

    @Test
    fun backupGuardFailsClosedWhenLegacyPlaintextCannotBeEncrypted() = runBlocking {
        val dao = FakeVariableDao(
            VariableEntity(projectId = 0, name = "API_TOKEN", value = "legacy-plaintext", isSecret = true),
        )
        val repository = VariableRepository(
            dao,
            object : VariableSecretCodec {
                override fun encrypt(variableName: String, plaintext: String): String = error("Keystore unavailable")
                override fun decrypt(variableName: String, envelope: String): Result<String> = Result.failure(
                    IllegalStateException("Keystore unavailable"),
                )
            },
        )

        val failure = runCatching { repository.requireEncryptedSecretRows() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("legacy-plaintext", dao.get(0, "API_TOKEN")!!.value)
    }

    private fun codec(key: SecretKey): VariableSecretCodec = AesGcmVariableSecretCodec(keyProvider = { key })

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    /**
     * In-memory [VariableDao]. A row's identity is **(projectId, name)** — the fork scopes variables by
     * project bucket and derives global/local from the name's case, so the old single-key `name` lookup
     * would silently merge a project-scoped `%DT_Ampmn` with the super-global of the same name.
     */
    private class FakeVariableDao(vararg initial: VariableEntity) : VariableDao {
        private val state = MutableStateFlow(initial.toList())

        private fun VariableEntity.sameRowAs(other: VariableEntity) =
            projectId == other.projectId && name == other.name

        override suspend fun insert(v: VariableEntity) {
            state.value = state.value.filterNot { it.sameRowAs(v) } + v
        }

        override suspend fun insertAll(values: List<VariableEntity>) {
            values.forEach { insert(it) }
        }

        override suspend fun update(v: VariableEntity) {
            insert(v)
        }

        override suspend fun delete(v: VariableEntity) {
            delete(v.projectId, v.name)
        }

        override suspend fun delete(projectId: Long, name: String) {
            state.value = state.value.filterNot { it.projectId == projectId && it.name == name }
        }

        override suspend fun deleteDangling(): Int = 0

        override suspend fun deleteStaleEventVars(): Int {
            val stale = state.value.filter {
                it.projectId == 0L && (it.name.startsWith("INTENT_") || it.name.startsWith("NOTIF_"))
            }
            state.value = state.value - stale.toSet()
            return stale.size
        }

        override suspend fun get(projectId: Long, name: String): VariableEntity? =
            state.value.firstOrNull { it.projectId == projectId && it.name == name }

        override suspend fun getAll(): List<VariableEntity> = state.value

        override fun getAllAsFlow(): Flow<List<VariableEntity>> = state
    }
}
