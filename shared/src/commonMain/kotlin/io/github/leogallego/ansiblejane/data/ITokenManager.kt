package io.github.leogallego.ansiblejane.data

import io.github.leogallego.ansiblejane.model.AapInstance
import io.github.leogallego.ansiblejane.model.InstanceInfo
import io.github.leogallego.ansiblejane.model.McpServerConfig
import io.github.leogallego.ansiblejane.model.ApiVersion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ITokenManager {
    val instances: StateFlow<List<AapInstance>>
    val activeInstance: StateFlow<AapInstance?>
    val isLoggedIn: Flow<Boolean>

    suspend fun saveInstance(
        baseUrl: String,
        token: String,
        alias: String? = null,
        apiVersion: ApiVersion,
        trustSelfSigned: Boolean = false,
        certFingerprint: String? = null,
        existingId: String? = null
    ): String

    suspend fun removeInstance(instanceId: String): Boolean
    suspend fun setActiveInstance(instanceId: String)
    fun getInstanceById(instanceId: String): AapInstance?
    suspend fun loadCredentials(): Boolean

    suspend fun saveCredentials(
        baseUrl: String,
        token: String,
        apiVersion: ApiVersion,
        trustSelfSigned: Boolean = false,
        certFingerprint: String? = null,
        alias: String? = null,
        existingId: String? = null
    ): String

    suspend fun updateInstanceInfo(
        instanceId: String,
        instanceInfo: InstanceInfo
    )

    suspend fun updateMcpConfig(
        instanceId: String,
        enabled: Boolean,
        servers: List<McpServerConfig>?
    )

    /** Persist `/api/v2/me/` RBAC flags for ToolRouter auditor filtering. */
    suspend fun updateUserRole(
        instanceId: String,
        isSuperuser: Boolean,
        isSystemAuditor: Boolean
    )

    suspend fun clearCredentials()

    suspend fun saveLlmApiKey(providerKey: String, apiKey: String)
    suspend fun loadLlmApiKey(providerKey: String): String?
    suspend fun loadAllLlmApiKeys(): Map<String, String>
    suspend fun clearLlmApiKeys()
}
