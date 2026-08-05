package io.github.leogallego.ansiblejane.assistant.llm

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider as KoogLLMProvider
import ai.koog.prompt.streaming.StreamFrame
import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.assistant.engine.DebugLog as Log
import io.github.leogallego.ansiblejane.network.createPlatformHttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import io.ktor.client.network.sockets.SocketTimeoutException

class KoogLlmProvider(
    private val config: LlmProviderConfig.OpenAiCompatible,
    trustSelfSigned: Boolean = false
) : LlmProvider, AutoCloseable {

    // Koog 1.1.1 emits Tool.Call.args verbatim (#2095), so the previous
    // FixedOpenAILLMClient double-encoding unwrap is no longer needed.
    private val client: OpenAILLMClient = run {
        val parsed = io.ktor.http.Url(config.url.trimEnd('/'))
        val baseUrl = "${parsed.protocol.name}://${parsed.host}" +
            if (parsed.port != parsed.protocol.defaultPort) ":${parsed.port}" else ""
        val pathPrefix = parsed.encodedPath.trimStart('/').trimEnd('/')
        val chatPath = if (pathPrefix.isNotEmpty()) "$pathPrefix/chat/completions"
            else "v1/chat/completions"
        val settings = OpenAIClientSettings(
            baseUrl = baseUrl,
            chatCompletionsPath = chatPath
        )
        val factory = if (trustSelfSigned) {
            KtorKoogHttpClient.Factory(baseClient = createPlatformHttpClient(trustSelfSigned = true))
        } else {
            KtorKoogHttpClient.Factory()
        }
        OpenAILLMClient(
            apiKey = config.apiKey ?: "",
            settings = settings,
            httpClientFactory = factory
        )
    }

    private val model = LLModel(
        provider = KoogLLMProvider.OpenAI,
        id = config.model,
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Tools,
            LLMCapability.Temperature,
            LLMCapability.OpenAIEndpoint.Completions
        )
    )

    override fun generateStream(
        prompt: Prompt,
        tools: List<ToolDescriptor>,
        maxTokens: Int?
    ): Flow<StreamFrame> = flow {
        Log.d(TAG, "Request: model=${config.model}, tools=${tools.size}, messages=${prompt.messages.size}")
        val startTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
        var frameCount = 0
        client.executeStreaming(prompt, model, tools).collect { frame ->
            frameCount++
            emit(frame)
        }
        Log.d(TAG, "Complete: ${frameCount} frames in ${kotlin.time.Clock.System.now().toEpochMilliseconds() - startTime}ms")
    }.catch { e ->
        Log.d(TAG, "Error: ${e::class.simpleName}: ${e.message}")
        throw mapException(e)
    }

    override fun isAvailable(): Boolean =
        config.url.isNotBlank() && config.model.isNotBlank()

    override fun modelInfo(): ModelInfo = ModelInfo(
        name = config.model,
        isLocal = false
    )

    override fun close() {
        client.close()
    }

    companion object {
        private const val TAG = "KoogLlmProvider"
    }

    internal fun mapException(e: Throwable): Throwable = when (e) {
        is LlmAuthException, is LlmRateLimitException,
        is LlmServerException, is LlmTimeoutException -> e
        is LLMClientException -> {
            val cause = e.cause
            if (cause != null) mapException(cause) else LlmServerException("LLM error: ${e.message}")
        }
        is KoogHttpClientException -> {
            val code = e.statusCode
            when {
                code == 401 || code == 403 -> LlmAuthException("Authentication failed: ${e.message}")
                code == 429 -> LlmRateLimitException("Rate limited: ${e.message}")
                code != null && code >= 500 -> LlmServerException("Server error ($code): ${e.message}")
                code != null -> LlmServerException("Client error ($code): ${e.message}")
                else -> LlmServerException("HTTP error: ${e.message}")
            }
        }
        is ClientRequestException -> {
            val code = e.response.status.value
            when (code) {
                401, 403 -> LlmAuthException("Authentication failed: ${e.message}")
                429 -> LlmRateLimitException("Rate limited: ${e.message}")
                else -> LlmServerException("Client error ($code): ${e.message}")
            }
        }
        is ServerResponseException -> LlmServerException("Server error: ${e.message}")
        is SocketTimeoutException -> LlmTimeoutException("Request timed out")
        else -> e
    }
}
