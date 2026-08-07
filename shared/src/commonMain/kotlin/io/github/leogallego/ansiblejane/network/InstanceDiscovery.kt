package io.github.leogallego.ansiblejane.network

import io.github.leogallego.ansiblejane.model.ApiVersion

import io.github.leogallego.ansiblejane.assistant.engine.DebugLog as Log
import io.github.leogallego.ansiblejane.model.AapComponent
import io.github.leogallego.ansiblejane.model.InstanceInfo
import io.github.leogallego.ansiblejane.model.PlatformType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import kotlin.coroutines.cancellation.CancellationException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class InstanceDiscovery(private val json: Json) {

    companion object {
        private const val TAG = "InstanceDiscovery"
    }

    suspend fun discover(
        baseUrl: String,
        token: String,
        apiVersion: ApiVersion,
        trustSelfSigned: Boolean = false
    ): InstanceInfo {
        val normalizedUrl = baseUrl.trimEnd('/')
        val client = createPlatformHttpClient(trustSelfSigned) {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
            defaultRequest {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        return try {
            discoverWithClient(client, normalizedUrl, apiVersion, token)
        } finally {
            client.close()
        }
    }

    private suspend fun discoverWithClient(
        client: HttpClient,
        normalizedUrl: String,
        apiVersion: ApiVersion,
        token: String
    ): InstanceInfo = coroutineScope {
        Log.d(TAG, "Probing $normalizedUrl (apiVersion=$apiVersion)")
        val components = mutableSetOf(AapComponent.CONTROLLER)

        val controllerPingDeferred = async {
            probeVersionEndpoint(client, "$normalizedUrl${apiVersion.prefix}ping/")
        }
        val configDeferred = async {
            probeConfig(client, normalizedUrl, apiVersion)
        }
        val gatewayPingDeferred = async {
            probeVersionEndpoint(client, "$normalizedUrl/api/gateway/v1/ping/")
        }
        val edaDeferred = async {
            probeVersionEndpoint(client, "$normalizedUrl/api/eda/v1/config/")
        }
        val hubDeferred = async {
            probeEndpoint(client, "$normalizedUrl/api/galaxy/_ui/v1/me/")
        }

        val controllerPing = controllerPingDeferred.await()
        val configResult = configDeferred.await()
        val gatewayPing = gatewayPingDeferred.await()
        val edaResult = edaDeferred.await()
        val hasHub = hubDeferred.await()

        val hasGateway = gatewayPing != null
        val hasEda = edaResult != null

        if (hasGateway) components.add(AapComponent.GATEWAY)
        if (hasEda) components.add(AapComponent.EDA)
        if (hasHub) components.add(AapComponent.HUB)

        val controllerVersion = controllerPing?.version ?: ""
        val gatewayVersion = gatewayPing?.productVersion
            ?: gatewayPing?.version ?: ""
        val edaVersion = edaResult?.version ?: ""
        val hasLicense = configResult?.hasLicense ?: false
        val couldReachController = controllerPing != null || configResult != null

        val platformType = derivePlatformType(hasGateway, hasLicense, couldReachController)
        val aapVersion = gatewayVersion.ifBlank { null }

        Log.d(TAG, "Discovery: controller=$controllerVersion, gateway=$gatewayVersion, " +
            "eda=$edaVersion, platform=$platformType, aap=$aapVersion, components=$components")

        InstanceInfo(
            controllerVersion = controllerVersion,
            gatewayVersion = gatewayVersion,
            edaVersion = edaVersion,
            platformType = platformType.name,
            aapVersion = aapVersion,
            components = components.map { it.name }
        )
    }

    private fun derivePlatformType(
        hasGateway: Boolean,
        hasLicense: Boolean,
        couldReachController: Boolean
    ): PlatformType = when {
        hasLicense -> PlatformType.AAP
        hasGateway -> PlatformType.JEWEL
        couldReachController -> PlatformType.AWX
        else -> PlatformType.UNKNOWN
    }

    private data class VersionResult(
        val version: String,
        val productVersion: String? = null,
    )

    private suspend fun probeVersionEndpoint(
        client: HttpClient,
        url: String
    ): VersionResult? = try {
        val response = client.get(url)
        if (response.status.isSuccess()) {
            val body = response.bodyAsText()
            val jsonObj = json.parseToJsonElement(body).jsonObject
            val version = jsonObj["version"]?.jsonPrimitive?.content ?: ""
            val headerVersion = response.headers["X-API-Product-Version"]
            VersionResult(version, headerVersion)
        } else {
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "Version probe $url failed: ${e.message}")
        null
    }

    private data class ConfigResult(val hasLicense: Boolean)

    private suspend fun probeConfig(
        client: HttpClient,
        baseUrl: String,
        apiVersion: ApiVersion
    ): ConfigResult? = try {
        val url = "$baseUrl${apiVersion.prefix}config/"
        val response = client.get(url)
        if (response.status.isSuccess()) {
            val body = response.bodyAsText()
            val jsonObj = json.parseToJsonElement(body).jsonObject
            val licenseInfo = jsonObj["license_info"]
            val hasLicense = when {
                licenseInfo == null -> false
                licenseInfo !is JsonObject -> false
                licenseInfo.jsonObject.isEmpty() -> false
                licenseInfo.jsonObject["license_type"]?.jsonPrimitive?.content == "open" -> false
                else -> true
            }
            ConfigResult(hasLicense)
        } else {
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "Config probe failed: ${e.message}")
        null
    }

    private suspend fun probeEndpoint(
        client: HttpClient,
        url: String
    ): Boolean = try {
        val response = client.get(url)
        response.status.isSuccess()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "Probe $url failed: ${e.message}")
        false
    }
}
