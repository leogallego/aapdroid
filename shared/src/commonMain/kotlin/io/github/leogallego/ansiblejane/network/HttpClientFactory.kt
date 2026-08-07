package io.github.leogallego.ansiblejane.network

import io.github.leogallego.ansiblejane.model.ApiVersion

import io.github.leogallego.ansiblejane.assistant.engine.DebugLog
import io.github.leogallego.ansiblejane.data.ITokenManager
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json

private data class ClientGroup(
    val controller: AapApiClient,
    val eda: EdaApiClient? = null,
    val platform: PlatformApiClient? = null,
    val hub: HubApiClient? = null,
    val httpClients: List<HttpClient> = emptyList()
)

class HttpClientFactory(
    private val tokenManager: ITokenManager,
    private val json: Json,
    private val logLevel: LogLevel = LogLevel.NONE
) : IAapApiProvider, SynchronizedObject() {
    private val clientCache = mutableMapOf<String, ClientGroup>()

    override fun getApiService(): AapApiClient = synchronized(this) {
        val instance = tokenManager.activeInstance.value
            ?: throw IllegalStateException("No active AAP instance. Please log in first.")

        val cached = clientCache[instance.id]
        if (cached != null) return@synchronized cached.controller

        val apiVersion = resolveApiVersion(instance.apiVersion)
        val client = buildClient(instance.baseUrl, apiVersion.prefix, instance.trustSelfSigned, instance.id)
        val apiClient = AapApiClient(client)
        clientCache[instance.id] = ClientGroup(controller = apiClient, httpClients = listOf(client))
        apiClient
    }

    override fun getEdaApiService(): EdaApiClient = getOrCreateSecondaryService(
        basePath = "/api/eda/v1/",
        getCached = { it.eda },
        createClient = { EdaApiClient(it) },
        updateGroup = { group, client, clients -> group.copy(eda = client, httpClients = clients) }
    )

    override fun getPlatformApiService(): PlatformApiClient = getOrCreateSecondaryService(
        basePath = "/api/gateway/v1/",
        getCached = { it.platform },
        createClient = { PlatformApiClient(it) },
        updateGroup = { group, client, clients -> group.copy(platform = client, httpClients = clients) }
    )

    override fun getHubApiService(): HubApiClient = getOrCreateSecondaryService(
        basePath = "/api/galaxy/",
        getCached = { it.hub },
        createClient = { HubApiClient(it) },
        updateGroup = { group, client, clients -> group.copy(hub = client, httpClients = clients) }
    )

    private fun <T> getOrCreateSecondaryService(
        basePath: String,
        getCached: (ClientGroup) -> T?,
        createClient: (HttpClient) -> T,
        updateGroup: (ClientGroup, T, List<HttpClient>) -> ClientGroup
    ): T = synchronized(this) {
        val instance = tokenManager.activeInstance.value
            ?: throw IllegalStateException("No active AAP instance. Please log in first.")

        val cached = clientCache[instance.id]
        val existing = cached?.let(getCached)
        if (existing != null) return@synchronized existing

        val client = buildClient(instance.baseUrl, basePath, instance.trustSelfSigned, instance.id)
        val serviceClient = createClient(client)

        val existingClients = cached?.httpClients ?: emptyList()
        val newClients = mutableListOf(client)
        val controllerClient = cached?.controller ?: run {
            val apiVersion = resolveApiVersion(instance.apiVersion)
            val ctrlHttp = buildClient(instance.baseUrl, apiVersion.prefix, instance.trustSelfSigned, instance.id)
            newClients.add(ctrlHttp)
            AapApiClient(ctrlHttp)
        }
        val base = cached ?: ClientGroup(controller = controllerClient)
        clientCache[instance.id] = updateGroup(base, serviceClient, existingClients + newClients)
        serviceClient
    }

    override fun evictInstance(instanceId: String) {
        synchronized(this) {
            clientCache.remove(instanceId)?.httpClients?.forEach { it.close() }
        }
    }

    private fun resolveApiVersion(apiVersionStr: String): ApiVersion = try {
        ApiVersion.valueOf(apiVersionStr)
    } catch (_: Exception) {
        ApiVersion.CONTROLLER_V2
    }

    private fun buildClient(
        baseUrl: String,
        basePath: String,
        trustSelfSigned: Boolean,
        instanceId: String
    ): HttpClient {
        val instance = tokenManager.getInstanceById(instanceId)
        val instanceToken = instance?.token
        val certFingerprint = instance?.certFingerprint
        return createPlatformHttpClient(trustSelfSigned, certFingerprint) {
            expectSuccess = true

            install(ContentNegotiation) { json(json) }

            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }

            install(Logging) {
                level = logLevel
                sanitizeHeader { header -> header == HttpHeaders.Authorization }
            }

            defaultRequest {
                url("${baseUrl.trimEnd('/')}$basePath")
                if (instanceToken != null) {
                    header(HttpHeaders.Authorization, "Bearer $instanceToken")
                }
            }

            HttpResponseValidator {
                validateResponse { response ->
                    if (response.status.value == 401) {
                        DebugLog.w(TAG, "401 Unauthorized for instance=$instanceId — emitting auth event")
                        AuthEvents.emitUnauthorized(instanceId)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "HttpClientFactory"
    }
}
