package io.github.leogallego.ansiblejane.data

import io.github.leogallego.ansiblejane.model.AapInstance
import io.github.leogallego.ansiblejane.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

sealed class CredentialStatus {
    data class Valid(val user: User) : CredentialStatus()
    data object NoCredentials : CredentialStatus()
    data class ValidationFailed(val error: Throwable) : CredentialStatus()
}

interface IAuthRepository {
    suspend fun validateCredentials(
        baseUrl: String,
        token: String,
        trustSelfSigned: Boolean,
        alias: String? = null,
        existingInstanceId: String? = null
    ): Result<User>

    suspend fun reAuthenticate(instanceId: String, newToken: String): Result<User>
    suspend fun checkExistingCredentials(): CredentialStatus
    suspend fun logoutInstance(instanceId: String)
    suspend fun logout()
    fun isLoggedIn(): Flow<Boolean>

    /**
     * Persist instance edits (token/alias/trust) and evict cached HTTP clients when
     * auth-affecting fields change.
     */
    suspend fun saveInstanceEdits(
        instanceId: String,
        token: String?,
        alias: String?,
        trustSelfSigned: Boolean
    ): Result<AapInstance>

    /** Re-run instance discovery and persist [InstanceInfo]. */
    suspend fun refreshInstanceInfo(instanceId: String): Result<Unit>

    /** 401 unauthorized events emitted by the HTTP layer. */
    val unauthorizedEvent: SharedFlow<String>
}
