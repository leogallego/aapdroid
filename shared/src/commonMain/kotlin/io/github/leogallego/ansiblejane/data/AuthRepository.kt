package io.github.leogallego.ansiblejane.data

import io.github.leogallego.ansiblejane.model.AapInstance
import io.github.leogallego.ansiblejane.model.ApiVersion
import io.github.leogallego.ansiblejane.model.User
import io.github.leogallego.ansiblejane.network.AapApiClient
import io.github.leogallego.ansiblejane.network.ApiVersionDetector
import io.github.leogallego.ansiblejane.network.AuthEvents
import io.github.leogallego.ansiblejane.network.IAapApiProvider
import io.github.leogallego.ansiblejane.network.InstanceDiscovery
import io.github.leogallego.ansiblejane.network.createPlatformHttpClient
import io.github.leogallego.ansiblejane.network.networkJson
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

class AuthRepository(
    private val tokenManager: ITokenManager,
    private val apiProvider: IAapApiProvider,
    private val instanceDiscovery: InstanceDiscovery,
    private val scope: CoroutineScope
) : IAuthRepository {

    override val unauthorizedEvent: SharedFlow<String> = AuthEvents.unauthorizedEvent

    override suspend fun validateCredentials(
        baseUrl: String,
        token: String,
        trustSelfSigned: Boolean,
        alias: String?,
        existingInstanceId: String?
    ): Result<User> {
        return try {
            val detector = ApiVersionDetector()
            val apiVersion = detector.detect(baseUrl, token, trustSelfSigned)
            val user = withOneOffClient(baseUrl, apiVersion, token, trustSelfSigned) { api ->
                api.getMe().results.firstOrNull()
            } ?: return Result.failure(Exception("No user data returned"))

            val instanceId = tokenManager.saveCredentials(
                baseUrl = baseUrl,
                token = token,
                apiVersion = apiVersion,
                trustSelfSigned = trustSelfSigned,
                alias = alias,
                existingId = existingInstanceId
            )
            tokenManager.updateUserRole(
                instanceId = instanceId,
                isSuperuser = user.isSuperuser,
                isSystemAuditor = user.isSystemAuditor
            )

            apiProvider.evictInstance(instanceId)
            launchDiscovery(instanceId, baseUrl, token, apiVersion, trustSelfSigned)

            Result.success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun reAuthenticate(instanceId: String, newToken: String): Result<User> {
        return try {
            val instance = tokenManager.getInstanceById(instanceId)
                ?: return Result.failure(Exception("Instance not found"))

            val trustSelfSigned = instance.trustSelfSigned
            val apiVersion = try {
                ApiVersion.valueOf(instance.apiVersion)
            } catch (_: Exception) {
                ApiVersion.CONTROLLER_V2
            }
            val user = withOneOffClient(instance.baseUrl, apiVersion, newToken, trustSelfSigned) { api ->
                api.getMe().results.firstOrNull()
            } ?: return Result.failure(Exception("No user data returned"))

            tokenManager.saveCredentials(
                baseUrl = instance.baseUrl,
                token = newToken,
                apiVersion = apiVersion,
                trustSelfSigned = trustSelfSigned,
                alias = instance.alias,
                existingId = instanceId
            )
            tokenManager.updateUserRole(
                instanceId = instanceId,
                isSuperuser = user.isSuperuser,
                isSystemAuditor = user.isSystemAuditor
            )

            apiProvider.evictInstance(instanceId)
            launchDiscovery(instanceId, instance.baseUrl, newToken, apiVersion, trustSelfSigned)

            Result.success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun checkExistingCredentials(): CredentialStatus {
        if (!tokenManager.loadCredentials()) return CredentialStatus.NoCredentials
        val activeInstance = tokenManager.activeInstance.value
            ?: return CredentialStatus.NoCredentials

        return try {
            val apiVersion = try {
                ApiVersion.valueOf(activeInstance.apiVersion)
            } catch (_: Exception) {
                ApiVersion.CONTROLLER_V2
            }
            val user = withOneOffClient(
                activeInstance.baseUrl, apiVersion,
                activeInstance.token, activeInstance.trustSelfSigned
            ) { api ->
                api.getMe().results.firstOrNull()
            } ?: return CredentialStatus.ValidationFailed(
                Exception("No user data returned")
            )
            tokenManager.updateUserRole(
                instanceId = activeInstance.id,
                isSuperuser = user.isSuperuser,
                isSystemAuditor = user.isSystemAuditor
            )
            CredentialStatus.Valid(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CredentialStatus.ValidationFailed(e)
        }
    }

    override suspend fun logoutInstance(instanceId: String) {
        apiProvider.evictInstance(instanceId)
        tokenManager.removeInstance(instanceId)
    }

    override suspend fun logout() {
        tokenManager.clearCredentials()
    }

    override fun isLoggedIn() = tokenManager.isLoggedIn

    override suspend fun saveInstanceEdits(
        instanceId: String,
        token: String?,
        alias: String?,
        trustSelfSigned: Boolean
    ): Result<AapInstance> {
        return try {
            val instance = tokenManager.getInstanceById(instanceId)
                ?: return Result.failure(Exception("Instance not found"))
            val apiVersion = try {
                ApiVersion.valueOf(instance.apiVersion)
            } catch (_: Exception) {
                ApiVersion.CONTROLLER_V2
            }
            tokenManager.saveInstance(
                baseUrl = instance.baseUrl,
                token = token ?: instance.token,
                alias = alias,
                apiVersion = apiVersion,
                trustSelfSigned = trustSelfSigned,
                existingId = instanceId
            )
            if (token != null || trustSelfSigned != instance.trustSelfSigned) {
                apiProvider.evictInstance(instanceId)
            }
            val updated = tokenManager.instances.value.find { it.id == instanceId }
                ?: return Result.failure(Exception("Instance not found after save"))
            Result.success(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun refreshInstanceInfo(instanceId: String): Result<Unit> {
        return try {
            val instance = tokenManager.instances.value.find { it.id == instanceId }
                ?: return Result.failure(Exception("Instance not found"))
            val apiVersion = try {
                ApiVersion.valueOf(instance.apiVersion)
            } catch (_: Exception) {
                ApiVersion.CONTROLLER_V2
            }
            val info = instanceDiscovery.discover(
                instance.baseUrl, instance.token, apiVersion, instance.trustSelfSigned
            )
            tokenManager.updateInstanceInfo(instanceId, info)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun launchDiscovery(
        instanceId: String,
        baseUrl: String,
        token: String,
        apiVersion: ApiVersion,
        trustSelfSigned: Boolean
    ) {
        scope.launch {
            try {
                val info = instanceDiscovery.discover(baseUrl, token, apiVersion, trustSelfSigned)
                tokenManager.updateInstanceInfo(instanceId, info)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Discovery is best-effort
            }
        }
    }

    private suspend fun <T> withOneOffClient(
        baseUrl: String,
        apiVersion: ApiVersion,
        token: String,
        trustSelfSigned: Boolean,
        block: suspend (AapApiClient) -> T
    ): T {
        val client = createPlatformHttpClient(trustSelfSigned) {
            expectSuccess = true
            install(ContentNegotiation) { json(networkJson) }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
            defaultRequest {
                url("${baseUrl.trimEnd('/')}${apiVersion.prefix}")
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        return try {
            block(AapApiClient(client))
        } finally {
            client.close()
        }
    }
}
