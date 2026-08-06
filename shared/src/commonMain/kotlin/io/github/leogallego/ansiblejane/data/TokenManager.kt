package io.github.leogallego.ansiblejane.data

import io.github.leogallego.ansiblejane.assistant.engine.DebugLog as Log
import io.github.leogallego.ansiblejane.model.AapInstance
import io.github.leogallego.ansiblejane.model.InstanceInfo
import io.github.leogallego.ansiblejane.model.McpServerConfig
import io.github.leogallego.ansiblejane.network.ApiVersion
import io.github.leogallego.ansiblejane.platform.DataStoreFactory
import io.github.leogallego.ansiblejane.platform.SecureKeyStorage
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class SerializedInstance(
    val id: String,
    val encryptedUrl: String,
    val encryptedToken: String,
    val alias: String? = null,
    val apiVersion: String = "CONTROLLER_V2",
    val trustSelfSigned: Boolean = false,
    val certFingerprint: String? = null,
    val mcpServerUrls: List<McpServerConfig>? = null,
    val mcpEnabled: Boolean = false,
    val instanceInfo: InstanceInfo? = null,
    val isSuperuser: Boolean = false,
    val isSystemAuditor: Boolean = false
)

@Serializable
data class InstancesState(
    val instances: List<SerializedInstance> = emptyList(),
    val activeInstanceId: String? = null
)

@OptIn(ExperimentalEncodingApi::class, ExperimentalUuidApi::class)
class TokenManager(
    dataStoreFactory: DataStoreFactory,
    private val secureKeyStorage: SecureKeyStorage,
    private val manifestRepository: IToolManifestRepository
) : ITokenManager {

    private val credentialsDataStore = dataStoreFactory.createPreferencesDataStore("credentials")

    private val _instances = MutableStateFlow<List<AapInstance>>(emptyList())
    override val instances: StateFlow<List<AapInstance>> = _instances.asStateFlow()

    private val _activeInstance = MutableStateFlow<AapInstance?>(null)
    override val activeInstance: StateFlow<AapInstance?> = _activeInstance.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val TAG = "TokenManager"
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_API_VERSION = stringPreferencesKey("api_version")
        val KEY_TRUST_SELF_SIGNED = booleanPreferencesKey("trust_self_signed")
        val KEY_CERT_FINGERPRINT = stringPreferencesKey("cert_fingerprint")
        val KEY_INSTANCES_JSON = stringPreferencesKey("instances_json")
        val KEY_LLM_API_KEYS = stringPreferencesKey("llm_api_keys")
    }

    private fun encrypt(value: String): String {
        val ciphertext = secureKeyStorage.encrypt(value.encodeToByteArray())
        return Base64.encode(ciphertext)
    }

    private fun decrypt(encoded: String): String {
        val ciphertext = Base64.decode(encoded)
        val plaintext = secureKeyStorage.decrypt(ciphertext)
        return plaintext.decodeToString()
    }

    private fun toAapInstance(serialized: SerializedInstance): AapInstance {
        return AapInstance(
            id = serialized.id,
            baseUrl = decrypt(serialized.encryptedUrl),
            token = decrypt(serialized.encryptedToken),
            alias = serialized.alias,
            apiVersion = serialized.apiVersion,
            trustSelfSigned = serialized.trustSelfSigned,
            certFingerprint = serialized.certFingerprint,
            mcpServerUrls = serialized.mcpServerUrls?.map { decryptHeaders(it) },
            mcpEnabled = serialized.mcpEnabled,
            instanceInfo = serialized.instanceInfo,
            isSuperuser = serialized.isSuperuser,
            isSystemAuditor = serialized.isSystemAuditor
        )
    }

    private fun toSerialized(instance: AapInstance): SerializedInstance {
        return SerializedInstance(
            id = instance.id,
            encryptedUrl = encrypt(instance.baseUrl),
            encryptedToken = encrypt(instance.token),
            alias = instance.alias,
            apiVersion = instance.apiVersion,
            trustSelfSigned = instance.trustSelfSigned,
            certFingerprint = instance.certFingerprint,
            mcpServerUrls = instance.mcpServerUrls?.map { encryptHeaders(it) },
            mcpEnabled = instance.mcpEnabled,
            instanceInfo = instance.instanceInfo,
            isSuperuser = instance.isSuperuser,
            isSystemAuditor = instance.isSystemAuditor
        )
    }

    private fun encryptHeaders(config: McpServerConfig): McpServerConfig {
        if (config.headers.isEmpty()) return config
        return config.copy(headers = config.headers.mapValues { (_, v) -> encrypt(v) })
    }

    private fun decryptHeaders(config: McpServerConfig): McpServerConfig {
        if (config.headers.isEmpty()) return config
        return config.copy(
            headers = config.headers.mapNotNull { (k, v) ->
                try { k to decrypt(v) } catch (_: Exception) { null }
            }.toMap()
        )
    }

    private suspend fun readState(): InstancesState {
        val prefs = credentialsDataStore.data.first()
        val jsonString = prefs[KEY_INSTANCES_JSON] ?: return InstancesState()
        return try {
            json.decodeFromString<InstancesState>(jsonString)
        } catch (_: Exception) {
            InstancesState()
        }
    }

    private suspend fun writeState(state: InstancesState) {
        val jsonString = json.encodeToString(state)
        credentialsDataStore.edit { prefs ->
            prefs[KEY_INSTANCES_JSON] = jsonString
        }
        updateReactiveState(state)
    }

    private fun updateReactiveState(state: InstancesState) {
        val decryptedInstances = state.instances.mapNotNull { serialized ->
            try {
                toAapInstance(serialized)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decrypt instance ${serialized.id}: ${e.message}")
                null
            }
        }
        _instances.value = decryptedInstances
        _activeInstance.value = decryptedInstances.find { it.id == state.activeInstanceId }
    }

    override suspend fun saveInstance(
        baseUrl: String,
        token: String,
        alias: String?,
        apiVersion: ApiVersion,
        trustSelfSigned: Boolean,
        certFingerprint: String?,
        existingId: String?
    ): String {
        Log.d(TAG, "saveInstance: alias=${alias ?: "none"}, apiVersion=$apiVersion, existingId=$existingId")
        val normalizedUrl = baseUrl.trimEnd('/').lowercase()
        val state = readState()

        val duplicate = state.instances.find { serialized ->
            val id = existingId ?: ""
            if (serialized.id == id) return@find false
            try {
                val existingUrl = decrypt(serialized.encryptedUrl).trimEnd('/').lowercase()
                existingUrl == normalizedUrl
            } catch (_: Exception) {
                false
            }
        }
        if (duplicate != null) {
            val updatedInstances = state.instances.map {
                if (it.id == duplicate.id) {
                    it.copy(
                        encryptedToken = encrypt(token),
                        alias = alias?.ifBlank { null } ?: it.alias,
                        apiVersion = apiVersion.name,
                        trustSelfSigned = trustSelfSigned,
                        certFingerprint = certFingerprint
                    )
                } else it
            }
            writeState(InstancesState(instances = updatedInstances, activeInstanceId = duplicate.id))
            return duplicate.id
        }

        val instanceId = existingId ?: Uuid.random().toString()

        val updatedInstances = if (existingId != null) {
            state.instances.map {
                if (it.id == existingId) {
                    it.copy(
                        encryptedUrl = encrypt(baseUrl),
                        encryptedToken = encrypt(token),
                        alias = alias?.ifBlank { null } ?: it.alias,
                        apiVersion = apiVersion.name,
                        trustSelfSigned = trustSelfSigned,
                        certFingerprint = certFingerprint
                    )
                } else it
            }
        } else {
            val instance = AapInstance(
                id = instanceId,
                baseUrl = baseUrl,
                token = token,
                alias = alias?.ifBlank { null },
                apiVersion = apiVersion.name,
                trustSelfSigned = trustSelfSigned,
                certFingerprint = certFingerprint
            )
            state.instances + toSerialized(instance)
        }

        val activeId = when {
            state.instances.isEmpty() && existingId == null -> instanceId
            existingId != null -> existingId
            else -> instanceId
        }

        writeState(InstancesState(instances = updatedInstances, activeInstanceId = activeId))
        return instanceId
    }

    override suspend fun removeInstance(instanceId: String): Boolean {
        Log.d(TAG, "removeInstance: $instanceId")
        val state = readState()
        val updatedInstances = state.instances.filter { it.id != instanceId }
        if (updatedInstances.size == state.instances.size) return false

        manifestRepository.deleteManifest(instanceId)

        val newActiveId = if (state.activeInstanceId == instanceId) {
            updatedInstances.firstOrNull()?.id
        } else {
            state.activeInstanceId
        }

        writeState(InstancesState(instances = updatedInstances, activeInstanceId = newActiveId))
        return true
    }

    override suspend fun setActiveInstance(instanceId: String) {
        Log.d(TAG, "setActiveInstance: $instanceId")
        val state = readState()
        if (state.instances.none { it.id == instanceId }) return
        writeState(state.copy(activeInstanceId = instanceId))
    }

    override fun getInstanceById(instanceId: String): AapInstance? {
        return _instances.value.find { it.id == instanceId }
    }

    override suspend fun loadCredentials(): Boolean {
        val prefs = credentialsDataStore.data.first()

        if (prefs[KEY_INSTANCES_JSON] == null) {
            val hasLegacyKeys = prefs[KEY_BASE_URL] != null || prefs[KEY_TOKEN] != null
            if (hasLegacyKeys) {
                Log.d(TAG, "Clearing legacy single-instance keys")
                credentialsDataStore.edit { p ->
                    p.remove(KEY_BASE_URL)
                    p.remove(KEY_TOKEN)
                    p.remove(KEY_API_VERSION)
                    p.remove(KEY_TRUST_SELF_SIGNED)
                    p.remove(KEY_CERT_FINGERPRINT)
                }
            }
            updateReactiveState(InstancesState())
            return false
        }

        val state = readState()
        Log.d(TAG, "Loaded ${state.instances.size} instances, active=${state.activeInstanceId}")
        updateReactiveState(state)
        return state.instances.isNotEmpty()
    }

    override suspend fun saveCredentials(
        baseUrl: String,
        token: String,
        apiVersion: ApiVersion,
        trustSelfSigned: Boolean,
        certFingerprint: String?,
        alias: String?,
        existingId: String?
    ): String {
        return saveInstance(
            baseUrl = baseUrl,
            token = token,
            alias = alias,
            apiVersion = apiVersion,
            trustSelfSigned = trustSelfSigned,
            certFingerprint = certFingerprint,
            existingId = existingId
        )
    }

    override suspend fun updateInstanceInfo(instanceId: String, instanceInfo: InstanceInfo) {
        val state = readState()
        if (state.instances.none { it.id == instanceId }) return
        val updatedInstances = state.instances.map { serialized ->
            if (serialized.id == instanceId) {
                serialized.copy(instanceInfo = instanceInfo)
            } else serialized
        }
        writeState(state.copy(instances = updatedInstances))
    }

    override suspend fun updateMcpConfig(
        instanceId: String,
        enabled: Boolean,
        servers: List<McpServerConfig>?
    ) {
        val state = readState()
        val updatedInstances = state.instances.map { serialized ->
            if (serialized.id == instanceId) {
                serialized.copy(
                    mcpEnabled = enabled,
                    mcpServerUrls = servers?.map { encryptHeaders(it) }
                )
            } else serialized
        }
        writeState(state.copy(instances = updatedInstances))
    }

    override suspend fun updateUserRole(
        instanceId: String,
        isSuperuser: Boolean,
        isSystemAuditor: Boolean
    ) {
        val state = readState()
        if (state.instances.none { it.id == instanceId }) return
        val updatedInstances = state.instances.map { serialized ->
            if (serialized.id == instanceId) {
                serialized.copy(
                    isSuperuser = isSuperuser,
                    isSystemAuditor = isSystemAuditor
                )
            } else serialized
        }
        writeState(state.copy(instances = updatedInstances))
    }

    private suspend fun readEncryptedLlmApiKeys(): Map<String, String> {
        val prefs = credentialsDataStore.data.first()
        val keysJson = prefs[KEY_LLM_API_KEYS] ?: return emptyMap()
        return try {
            json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                keysJson
            )
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun writeEncryptedLlmApiKeys(keys: Map<String, String>) {
        val keysJson = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            keys
        )
        credentialsDataStore.edit { prefs ->
            prefs[KEY_LLM_API_KEYS] = keysJson
        }
    }

    override suspend fun saveLlmApiKey(providerKey: String, apiKey: String) {
        Log.d(TAG, "SAVE_KEY: provider=$providerKey, keyLen=${apiKey.length}")
        val keys = readEncryptedLlmApiKeys().toMutableMap()
        keys[providerKey] = encrypt(apiKey)
        writeEncryptedLlmApiKeys(keys)
        Log.d(TAG, "SAVE_KEY: done, ${keys.size} keys stored")
    }

    override suspend fun loadLlmApiKey(providerKey: String): String? {
        val allKeys = readEncryptedLlmApiKeys()
        Log.d(TAG, "LOAD_KEY: provider=$providerKey, storedKeys=${allKeys.keys}")
        val encrypted = allKeys[providerKey] ?: run {
            Log.d(TAG, "LOAD_KEY: NOT FOUND for $providerKey")
            return null
        }
        return try {
            val decrypted = decrypt(encrypted)
            Log.d(TAG, "LOAD_KEY: OK, decryptedLen=${decrypted.length}")
            decrypted
        } catch (e: Exception) {
            Log.d(TAG, "LOAD_KEY: DECRYPT FAILED: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    override suspend fun loadAllLlmApiKeys(): Map<String, String> {
        return readEncryptedLlmApiKeys().mapNotNull { (key, encrypted) ->
            try { key to decrypt(encrypted) } catch (_: Exception) { null }
        }.toMap()
    }

    override suspend fun clearLlmApiKeys() {
        credentialsDataStore.edit { prefs ->
            prefs.remove(KEY_LLM_API_KEYS)
        }
    }

    override suspend fun clearCredentials() {
        clearLlmApiKeys()
        credentialsDataStore.edit { it.clear() }
        _instances.value = emptyList()
        _activeInstance.value = null
    }

    override val isLoggedIn: Flow<Boolean> = credentialsDataStore.data.map { prefs ->
        val jsonString = prefs[KEY_INSTANCES_JSON]
        if (jsonString != null) {
            try {
                val state = json.decodeFromString<InstancesState>(jsonString)
                state.instances.isNotEmpty()
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
    }
}
