package io.github.leogallego.ansiblejane.fakes

import io.github.leogallego.ansiblejane.data.CredentialStatus
import io.github.leogallego.ansiblejane.data.IAuthRepository
import io.github.leogallego.ansiblejane.model.AapInstance
import io.github.leogallego.ansiblejane.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeAuthRepository(
    private val tokenManager: FakeTokenManager? = null
) : IAuthRepository {
    var validateResult: Result<User>? = null
    var shouldFail = false
    var failureException: Exception = RuntimeException("Test error")
    var existingCredentialsResult: CredentialStatus = CredentialStatus.NoCredentials
    val removedInstances = mutableListOf<String>()
    val evictedInstances = mutableListOf<String>()
    var saveInstanceEditsResult: Result<AapInstance>? = null
    var refreshInstanceInfoResult: Result<Unit> = Result.success(Unit)
    private val _isLoggedIn = MutableStateFlow(false)
    private val _unauthorizedEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val unauthorizedEvent: SharedFlow<String> = _unauthorizedEvent.asSharedFlow()

    override suspend fun validateCredentials(
        baseUrl: String,
        token: String,
        trustSelfSigned: Boolean,
        alias: String?,
        existingInstanceId: String?
    ): Result<User> {
        if (shouldFail) return Result.failure(failureException)
        return validateResult ?: Result.failure(RuntimeException("No result configured"))
    }

    override suspend fun reAuthenticate(instanceId: String, newToken: String): Result<User> {
        if (shouldFail) return Result.failure(failureException)
        return validateResult ?: Result.failure(RuntimeException("No result configured"))
    }

    override suspend fun checkExistingCredentials(): CredentialStatus {
        return existingCredentialsResult
    }

    override suspend fun logoutInstance(instanceId: String) {
        evictedInstances.add(instanceId)
        removedInstances.add(instanceId)
        tokenManager?.removeInstance(instanceId)
        _isLoggedIn.value = false
    }

    override suspend fun logout() {
        _isLoggedIn.value = false
    }

    override fun isLoggedIn(): Flow<Boolean> = _isLoggedIn

    override suspend fun saveInstanceEdits(
        instanceId: String,
        token: String?,
        alias: String?,
        trustSelfSigned: Boolean
    ): Result<AapInstance> {
        saveInstanceEditsResult?.let { return it }
        val instance = tokenManager?.getInstanceById(instanceId)
            ?: return Result.failure(Exception("Instance not found"))
        if (token != null || trustSelfSigned != instance.trustSelfSigned) {
            evictedInstances.add(instanceId)
        }
        // Mirror persistence lightly for UI state tests when a FakeTokenManager is provided.
        tokenManager?.let { tm ->
            val updated = instance.copy(
                token = token ?: instance.token,
                alias = alias,
                trustSelfSigned = trustSelfSigned
            )
            val list = tm.instances.value.map { if (it.id == instanceId) updated else it }
            tm.setInstances(list)
            return Result.success(updated)
        }
        return Result.success(instance)
    }

    override suspend fun refreshInstanceInfo(instanceId: String): Result<Unit> {
        return refreshInstanceInfoResult
    }

    fun setLoggedIn(loggedIn: Boolean) {
        _isLoggedIn.value = loggedIn
    }

    fun emitUnauthorized(instanceId: String) {
        _unauthorizedEvent.tryEmit(instanceId)
    }
}
