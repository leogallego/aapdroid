package io.github.leogallego.ansiblejane.network

import io.github.leogallego.ansiblejane.model.ApiVersion

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class ApiVersionDetector {

    suspend fun detect(
        baseUrl: String,
        token: String,
        trustSelfSigned: Boolean = false
    ): ApiVersion {
        val normalizedUrl = baseUrl.trimEnd('/')
        val client = createPlatformHttpClient(trustSelfSigned) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
            defaultRequest {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        return try {
            val response = client.get("$normalizedUrl${ApiVersion.CONTROLLER_V2.prefix}me/")
            if (response.status.value != 404) ApiVersion.CONTROLLER_V2 else ApiVersion.V2
        } catch (_: Exception) {
            ApiVersion.V2
        } finally {
            client.close()
        }
    }
}
