package com.opentasker.core.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.Variable
import com.opentasker.core.model.VariableNamePolicy
import com.opentasker.core.model.DEFAULT_PROJECT_ID
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Authenticated encryption boundary for values marked as first-class secrets. */
interface VariableSecretCodec {
    fun encrypt(variableName: String, plaintext: String): String
    fun decrypt(variableName: String, envelope: String): Result<String>
}

/**
 * AES-256-GCM envelope codec. The variable name is authenticated as AAD so ciphertext cannot be
 * copied to a different variable and still decrypt. The key itself is supplied by Android
 * Keystore in production and by an in-memory key in JVM tests.
 */
class AesGcmVariableSecretCodec(
    private val keyProvider: () -> SecretKey,
    private val secureRandom: SecureRandom = SecureRandom(),
) : VariableSecretCodec {
    override fun encrypt(variableName: String, plaintext: String): String {
        val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        require(plaintextBytes.size <= MAX_SECRET_PLAINTEXT_BYTES) {
            "Secret value exceeds $MAX_SECRET_PLAINTEXT_BYTES UTF-8 bytes."
        }
        val cipher = Cipher.getInstance(ALGORITHM)
        // Android Keystore keys created with randomized encryption reject a caller-supplied IV.
        // Let the provider generate it, then persist that authenticated nonce in the envelope.
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider(), secureRandom)
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES) { "Secret cipher generated an invalid IV." }
        cipher.updateAAD(variableName.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(plaintextBytes)
        return listOf(
            ENVELOPE_PREFIX,
            encoder.encodeToString(iv),
            encoder.encodeToString(encrypted),
        ).joinToString(ENVELOPE_SEPARATOR)
    }

    override fun decrypt(variableName: String, envelope: String): Result<String> = runCatching {
        require(envelope.length <= MAX_SECRET_ENVELOPE_CHARS) { "Secret envelope is oversized." }
        val parts = envelope.split(ENVELOPE_SEPARATOR)
        require(parts.size == 4 && parts[0] == "otsec" && parts[1] == "v1") {
            "Secret envelope is malformed or unsupported."
        }
        val iv = decoder.decode(parts[2])
        val encrypted = decoder.decode(parts[3])
        require(iv.size == GCM_IV_BYTES) { "Secret envelope IV is invalid." }
        require(encrypted.size in MIN_GCM_CIPHERTEXT_BYTES..MAX_SECRET_CIPHERTEXT_BYTES) {
            "Secret envelope ciphertext is invalid."
        }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(variableName.toByteArray(StandardCharsets.UTF_8))
        cipher.doFinal(encrypted).toString(StandardCharsets.UTF_8)
    }

    companion object {
        internal const val MAX_SECRET_PLAINTEXT_BYTES = 65_536
        private const val MAX_SECRET_ENVELOPE_CHARS = 100_000
        private const val MAX_SECRET_CIPHERTEXT_BYTES = MAX_SECRET_PLAINTEXT_BYTES + 16
        private const val MIN_GCM_CIPHERTEXT_BYTES = 16
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val ENVELOPE_PREFIX = "otsec:v1"
        private const val ENVELOPE_SEPARATOR = ":"
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        internal fun isEnvelope(value: String): Boolean = runCatching {
            if (value.length > MAX_SECRET_ENVELOPE_CHARS) return@runCatching false
            val parts = value.split(ENVELOPE_SEPARATOR)
            if (parts.size != 4 || parts[0] != "otsec" || parts[1] != "v1") {
                return@runCatching false
            }
            val iv = decoder.decode(parts[2])
            val encrypted = decoder.decode(parts[3])
            iv.size == GCM_IV_BYTES && encrypted.size in MIN_GCM_CIPHERTEXT_BYTES..MAX_SECRET_CIPHERTEXT_BYTES
        }.getOrDefault(false)
    }
}

/** Android Keystore key provider. Keystore keys are deliberately absent from database backups. */
class AndroidKeystoreVariableSecretCodec : VariableSecretCodec {
    private val delegate = AesGcmVariableSecretCodec(::getOrCreateKey)

    override fun encrypt(variableName: String, plaintext: String): String =
        delegate.encrypt(variableName, plaintext)

    override fun decrypt(variableName: String, envelope: String): Result<String> =
        delegate.decrypt(variableName, envelope)

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        internal const val KEY_ALIAS = "opentasker.variable-secrets.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

object VariableSecretCodecs {
    val android: VariableSecretCodec by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidKeystoreVariableSecretCodec()
    }
}

data class RuntimeVariableSeed(
    val values: Map<String, String>,
    val secretNames: Set<String>,
    val unavailableSecretNames: Set<String>,
)

data class RuntimeVariableValue(
    val name: String,
    val value: String,
    val isSecret: Boolean,
    val isGlobal: Boolean = true,
    val projectId: Long = DEFAULT_PROJECT_ID,
)

data class RuntimeVariableCommitResult(
    val appliedNames: List<String>,
    val conflictedNames: List<String>,
)

data class OrdinaryVariableExport(
    val variables: List<Variable>,
    val omittedSecretCount: Int,
)

/**
 * The only supported plaintext/ciphertext crossing point for persisted variables. Callers receive
 * plaintext domain/runtime values but Room receives ciphertext for every secret row.
 */
class VariableRepository(
    private val dao: VariableDao,
    private val secretCodec: VariableSecretCodec = VariableSecretCodecs.android,
) {
    private val migrationMutex = Mutex()
    @Volatile private var legacyMigrationAttempted = false

    fun observeGlobals(projectId: Long? = DEFAULT_PROJECT_ID): Flow<List<Variable>> = flow {
        migrateLegacySensitiveVariables()
        val source = when (projectId) {
            null -> dao.getAllGlobalAsFlowAll()
            DEFAULT_PROJECT_ID -> dao.getAllGlobalAsFlow()
            else -> dao.getAllGlobalAsFlowInProject(projectId)
        }
        emitAll(
            source.map { entities ->
                entities.map(::decodeForDomain)
            },
        )
    }

    suspend fun upsert(variable: Variable) {
        storageMutationMutex.withLock {
            dao.upsert(variable.normalizedForStorage().toStoredEntity(secretCodec))
        }
    }

    suspend fun get(name: String, projectId: Long = DEFAULT_PROJECT_ID): Variable? {
        migrateLegacySensitiveVariables()
        val normalizedName = VariableNamePolicy.normalize(name) ?: return null
        val entity = if (projectId == DEFAULT_PROJECT_ID) {
            dao.get(normalizedName)
        } else {
            dao.getInProject(normalizedName, projectId)
        }
        return entity?.let(::decodeForDomain)
    }

    /** Stores an edited value under a new name and removes the old row as one mutation. */
    suspend fun rename(previousName: String, variable: Variable) {
        val normalized = variable.normalizedForStorage()
        val oldName = VariableNamePolicy.normalize(previousName)
            ?: throw IllegalArgumentException("Invalid variable name '$previousName'.")
        require(normalized.projectId == variable.projectId) { "Variable project scope changed during rename." }
        storageMutationMutex.withLock {
            val oldEntity = if (normalized.projectId == DEFAULT_PROJECT_ID) {
                dao.get(oldName)
            } else {
                dao.getInProject(oldName, normalized.projectId)
            } ?: throw IllegalStateException("Variable '%$oldName' no longer exists.")
            require(oldEntity.isGlobal == normalized.isGlobal) {
                "Variable scope cannot change during rename."
            }
            if (oldEntity.isSecret && !variable.secretAvailable && variable.value.isEmpty()) {
                throw IllegalStateException("Secret variable '%$oldName' must be entered again before it can be renamed.")
            }
            if (oldName != normalized.name) {
                val destination = if (normalized.projectId == DEFAULT_PROJECT_ID) {
                    dao.get(normalized.name)
                } else {
                    dao.getInProject(normalized.name, normalized.projectId)
                }
                require(destination == null) { "Variable '%${normalized.name}' already exists." }
            }

            dao.upsert(normalized.toStoredEntity(secretCodec))
            if (oldName != normalized.name) {
                if (normalized.projectId == DEFAULT_PROJECT_ID) dao.deleteByName(oldName)
                else dao.deleteByNameInProject(oldName, normalized.projectId)
            }
        }
    }

    suspend fun delete(name: String, projectId: Long = DEFAULT_PROJECT_ID) {
        val normalizedName = VariableNamePolicy.normalize(name)
            ?: throw IllegalArgumentException("Invalid variable name '$name'.")
        storageMutationMutex.withLock {
            if (projectId == DEFAULT_PROJECT_ID) dao.deleteByName(normalizedName)
            else dao.deleteByNameInProject(normalizedName, projectId)
        }
    }

    suspend fun importVariable(variable: Variable) {
        val normalized = variable.normalizedForStorage()
        val entity = normalized.toStoredEntity(secretCodec)
        storageMutationMutex.withLock {
            val existing = if (normalized.projectId == DEFAULT_PROJECT_ID) {
                dao.get(normalized.name)
            } else {
                dao.getInProject(normalized.name, normalized.projectId)
            }
            if (existing == null) dao.upsert(entity) else dao.upsert(entity)
        }
    }

    suspend fun ordinaryExport(projectId: Long? = null): OrdinaryVariableExport {
        migrateLegacySensitiveVariables()
        val entities = projectId?.let { dao.getAllInProject(it) } ?: dao.getAll()
        return OrdinaryVariableExport(
            variables = entities
                .filterNot(VariableEntity::isEffectivelySecret)
                .map(::decodeForDomain),
            omittedSecretCount = entities.count(VariableEntity::isEffectivelySecret),
        )
    }

    suspend fun runtimeGlobals(projectId: Long = DEFAULT_PROJECT_ID): RuntimeVariableSeed {
        migrateLegacySensitiveVariables()
        return readRuntimeGlobals(projectId)
    }

    private suspend fun readRuntimeGlobals(projectId: Long = DEFAULT_PROJECT_ID): RuntimeVariableSeed {
        val values = linkedMapOf<String, String>()
        val secretNames = linkedSetOf<String>()
        val unavailable = linkedSetOf<String>()
        val entities = if (projectId == DEFAULT_PROJECT_ID) dao.getAllGlobal() else dao.getAllGlobalInProject(projectId)
        entities.sortedBy { it.name }.forEach { entity ->
            if (!entity.isEffectivelySecret()) {
                values[entity.name] = entity.value
                return@forEach
            }

            secretNames += entity.name
            secretCodec.decrypt(entity.name, entity.value)
                .onSuccess { values[entity.name] = it }
                .onFailure { unavailable += entity.name }
        }
        return RuntimeVariableSeed(values, secretNames, unavailable)
    }

    suspend fun persistRuntime(values: List<RuntimeVariableValue>) {
        val entities = values.map(::runtimeValueToEntity)
        storageMutationMutex.withLock {
            dao.upsertAll(entities)
        }
    }

    /**
     * Applies a run's changed globals as one Room insert batch only when each row still matches the
     * snapshot that run hydrated. The first commit wins a same-name race; disjoint names merge.
     */
    suspend fun persistRuntimeAtomically(
        expected: RuntimeVariableSeed,
        values: List<RuntimeVariableValue>,
    ): RuntimeVariableCommitResult {
        if (values.isEmpty()) return RuntimeVariableCommitResult(emptyList(), emptyList())
        migrateLegacySensitiveVariables()
        return storageMutationMutex.withLock {
            val current = readRuntimeGlobals(values.firstOrNull()?.projectId ?: DEFAULT_PROJECT_ID)
            val accepted = mutableListOf<RuntimeVariableValue>()
            val appliedNames = mutableListOf<String>()
            val conflictedNames = mutableListOf<String>()
            values.sortedBy(RuntimeVariableValue::name).forEach { value ->
                val currentState = current.stateOf(value.name)
                val expectedState = expected.stateOf(value.name)
                val desiredState = RuntimeVariableState(value.value, value.isSecret, unavailable = false)
                when {
                    currentState == desiredState -> appliedNames += value.name
                    currentState == expectedState -> {
                        accepted += value
                        appliedNames += value.name
                    }
                    else -> conflictedNames += value.name
                }
            }
            if (accepted.isNotEmpty()) dao.upsertAll(accepted.map(::runtimeValueToEntity))
            RuntimeVariableCommitResult(appliedNames, conflictedNames)
        }
    }

    suspend fun migrateLegacySensitiveVariables() {
        if (legacyMigrationAttempted) return
        migrationMutex.withLock {
            if (legacyMigrationAttempted) return
            storageMutationMutex.withLock {
                dao.getAll()
                    .filter { it.isSecret && !AesGcmVariableSecretCodec.isEnvelope(it.value) }
                    .forEach { entity ->
                        runCatching {
                            dao.upsert(
                                entity.copy(
                                    value = secretCodec.encrypt(entity.name, entity.value),
                                    isSecret = true,
                                ),
                            )
                        }.onFailure { error ->
                            // Logging must never replace the encryption failure (notably in host-side
                            // migration tests where android.util.Log is unavailable).
                            runCatching {
                                AppLogger.error(TAG, "Failed to encrypt legacy masked variable ${entity.name}", error)
                            }
                        }
                    }
            }
            legacyMigrationAttempted = true
        }
    }

    /** Refuses backup/export paths while any flagged legacy value is still plaintext. */
    suspend fun requireEncryptedSecretRows() {
        migrateLegacySensitiveVariables()
        check(dao.getAll().none { it.isSecret && !AesGcmVariableSecretCodec.isEnvelope(it.value) }) {
            "One or more secret variables could not be encrypted; backup was refused."
        }
    }

    private fun decodeForDomain(entity: VariableEntity): Variable {
        if (!entity.isSecret) {
            return Variable(entity.name, entity.value, entity.isGlobal, projectId = entity.projectId)
        }
        val decoded = secretCodec.decrypt(entity.name, entity.value)
        return Variable(
            name = entity.name,
            value = decoded.getOrDefault(""),
            isGlobal = entity.isGlobal,
            isSecret = true,
            secretAvailable = decoded.isSuccess,
            projectId = entity.projectId,
        )
    }

    private fun runtimeValueToEntity(value: RuntimeVariableValue): VariableEntity =
        Variable(
            name = value.name,
            value = value.value,
        isGlobal = true,
        isSecret = value.isSecret,
        projectId = value.projectId,
        ).normalizedForStorage().toStoredEntity(secretCodec)

    companion object {
        private const val TAG = "VariableRepository"
        private val storageMutationMutex = Mutex()
    }
}

private data class RuntimeVariableState(
    val value: String?,
    val isSecret: Boolean,
    val unavailable: Boolean,
)

private fun RuntimeVariableSeed.stateOf(name: String): RuntimeVariableState = RuntimeVariableState(
    value = values[name],
    isSecret = name in secretNames,
    unavailable = name in unavailableSecretNames,
)

internal fun VariableEntity.isEffectivelySecret(): Boolean = isSecret

internal fun Variable.normalizedForStorage(): Variable {
    val normalizedName = VariableNamePolicy.normalizeForScope(name, isGlobal)
        ?: throw IllegalArgumentException(
            if (isGlobal) {
                "Invalid global variable name '$name'"
            } else {
                "Invalid local variable name '$name': local names must be all lowercase"
            },
        )
    return if (normalizedName == name) this else copy(name = normalizedName)
}

internal fun Variable.toStoredEntity(codec: VariableSecretCodec): VariableEntity = VariableEntity(
    name = name,
    value = if (isSecret) codec.encrypt(name, value) else value,
    isGlobal = isGlobal,
    isSecret = isSecret,
    projectId = projectId,
)
