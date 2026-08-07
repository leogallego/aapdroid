package io.github.leogallego.ansiblejane.assistant.data

import io.github.leogallego.ansiblejane.network.createPlatformHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One-shot LLM `/models` discovery for Settings.
 *
 * Owns its HTTP client lifecycle so presentation never calls
 * [createPlatformHttpClient] directly (service-contracts §1 ModelFetcher exception).
 *
 * [clientFactory] is injectable for tests; production uses the default platform client.
 */
class ModelFetcher(
    private val json: Json,
    private val clientFactory: (trustSelfSigned: Boolean) -> HttpClient = { trustSelfSigned ->
        createPlatformHttpClient(trustSelfSigned = trustSelfSigned) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
        }
    }
) {
    sealed interface Result {
        data class Success(val models: List<String>) : Result
        data class Error(val message: String) : Result
    }

    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String?,
        trustSelfSigned: Boolean = false
    ): Result {
        val url = "${baseUrl.trimEnd('/')}/models"
        val client = clientFactory(trustSelfSigned)
        return try {
            val response = try {
                client.get(url) {
                    if (!apiKey.isNullOrBlank()) {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.Error("Could not reach server: ${e.message}")
            }

            when (response.status.value) {
                in 200..299 -> parseModelsResponse(response.bodyAsText())
                401, 403 -> Result.Error("Authentication failed — check API key")
                else -> Result.Error("Server returned ${response.status.value}")
            }
        } finally {
            client.close()
        }
    }

    private fun parseModelsResponse(body: String): Result {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray ?: return Result.Success(emptyList())
            val models = data.mapNotNull { element ->
                element.jsonObject["id"]?.jsonPrimitive?.content
            }.sorted()
            Result.Success(models)
        } catch (e: Exception) {
            Result.Error("Failed to parse models: ${e.message}")
        }
    }
}
