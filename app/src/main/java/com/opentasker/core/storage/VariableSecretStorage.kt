package com.opentasker.core.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.Variable
import com.opentasker.core.model.VariableNamePolicy
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
)

data class RuntimeVariableCommitResult(
    val appliedNames: List<String>,
    val conflictedNames: List<String>,
)

data class OrdinaryVariableExport(
    val variables: List<Variable>,
    val omittedSecretCount: Int,
    val omittedSecretNames: Set<String> = emptySet(),
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

    fun observeGlobals(): Flow<List<Variable>> = flow {
        migrateLegacySensitiveVariables()
        emitAll(
            dao.getAllAsFlow().map { entities ->
                entities.map(::decodeForDomain)
            },
        )
    }

    suspend fun upsert(variable: Variable) {
        storageMutationMutex.withLock {
            dao.insert(variable.normalizedForStorage().toStoredEntity(secretCodec))
        }
    }

    suspend fun delete(projectId: Long, name: String) {
        storageMutationMutex.withLock { dao.delete(projectId, name) }
    }

    suspend fun importVariable(variable: Variable) {
        val normalized = variable.normalizedForStorage()
        val entity = normalized.toStoredEntity(secretCodec)
        storageMutationMutex.withLock {
            if (dao.get(normalized.projectId, normalized.name) == null) dao.insert(entity) else dao.update(entity)
        }
    }

    suspend fun ordinaryExport(): OrdinaryVariableExport {
        migrateLegacySensitiveVariables()
        val entities = dao.getAll()
        return OrdinaryVariableExport(
            variables = entities
                .filterNot(VariableEntity::isEffectivelySecret)
                .map(::decodeForDomain),
            omittedSecretCount = entities.count(VariableEntity::isEffectivelySecret),
            omittedSecretNames = entities
                .filter(VariableEntity::isEffectivelySecret)
                .mapTo(linkedSetOf(), VariableEntity::name),
        )
    }

    suspend fun runtimeGlobals(): RuntimeVariableSeed {
        migrateLegacySensitiveVariables()
        return readRuntimeGlobals()
    }

    private suspend fun readRuntimeGlobals(): RuntimeVariableSeed {
        val values = linkedMapOf<String, String>()
        val secretNames = linkedSetOf<String>()
        val unavailable = linkedSetOf<String>()
        dao.getAll().sortedBy { it.name }.forEach { entity ->
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
            dao.insertAll(entities)
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
            val current = readRuntimeGlobals()
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
            if (accepted.isNotEmpty()) dao.insertAll(accepted.map(::runtimeValueToEntity))
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
                            dao.update(
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
            return Variable(entity.name, entity.value, entity.projectId)
        }
        val decoded = secretCodec.decrypt(entity.name, entity.value)
        return Variable(
            name = entity.name,
            value = decoded.getOrDefault(""),
            projectId = entity.projectId,
            isSecret = true,
            secretAvailable = decoded.isSuccess,
        )
    }

    private fun runtimeValueToEntity(value: RuntimeVariableValue): VariableEntity =
        Variable(
            name = value.name,
            value = value.value,
            projectId = 0,
            isSecret = value.isSecret,
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
    // Fork: names may be non-ASCII (Japanese), so only strip an optional `%` sigil and trim —
    // VariableNamePolicy's ASCII-only pattern must not reject them here.
    val normalizedName = name.trim().removePrefix("%")
    require(normalizedName.isNotEmpty()) { "Empty variable name" }
    return if (normalizedName == name) this else copy(name = normalizedName)
}

internal fun Variable.toStoredEntity(codec: VariableSecretCodec): VariableEntity = VariableEntity(
    projectId = projectId,
    name = name,
    value = if (isSecret) codec.encrypt(name, value) else value,
    isSecret = isSecret,
)
