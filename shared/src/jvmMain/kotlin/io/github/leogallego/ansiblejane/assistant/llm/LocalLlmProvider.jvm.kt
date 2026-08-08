@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package io.github.leogallego.ansiblejane.assistant.llm

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.streaming.StreamFrame
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message as LiteRtMessage
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall as LiteRtToolCall
import com.google.ai.edge.litertlm.tool
import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.assistant.local.ILocalModelRepository
import io.github.leogallego.ansiblejane.assistant.local.LOCAL_MODEL_CATALOG
import io.github.leogallego.ansiblejane.assistant.local.resolveOnDeviceContextTokens
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * LiteRT-LM 0.15.0 sync-path [LlmProvider] for JVM/Desktop (#264 PR1).
 *
 * ## Spike: schema registration with `automaticToolCalling = false`
 * LiteRT's `ConversationConfig` exposes `tools: List<ToolProvider>` and `automaticToolCalling:
 * Boolean` as independent constructor parameters (confirmed by inspecting the litertlm-jvm
 * 0.15.0 jar's `ConversationConfig`/`Conversation` bytecode: `tools` accepts `OpenApiTool`
 * schema providers regardless of the auto-calling flag, and `Message.toolCalls` is populated by
 * the underlying function-calling grammar whenever tools are registered — the flag only controls
 * whether LiteRT *also* invokes `OpenApiTool.execute()` internally and loops). PR1 registers real
 * schemas via [ToolDescriptor.toOpenApiSchemaJson] with `automaticToolCalling = false`, so
 * ChatEngine (not LiteRT) executes tools; [SchemaOnlyOpenApiTool.execute] therefore throws — it
 * should be unreachable. This was verified statically against the 0.15.0 API surface; it has
 * **not** been exercised against a real downloaded model + native engine in this environment (no
 * GPU/model weights in the sandbox) — flagged as a manual-verification item under the PR1
 * acceptance criteria ("E4B tool-calling reliability" prototype step).
 */
class LocalLlmProvider internal constructor(
    private val config: LlmProviderConfig.OnDevice,
    private val modelRepository: ILocalModelRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : LlmProvider {

    private val _engineState = MutableStateFlow(LocalEngineState.Uninitialized)
    val engineState: StateFlow<LocalEngineState> = _engineState.asStateFlow()

    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var loadedModelId: String? = null
    @Volatile private var loadedContextTokens: Int? = null
    private val idleRelease = IdleReleaseScheduler(scope) { releaseEngine() }

    override fun generateStream(
        prompt: Prompt,
        tools: List<ToolDescriptor>,
        maxTokens: Int?,
    ): Flow<StreamFrame> = flow {
        idleRelease.cancel()
        val history = promptToBridgedHistory(prompt)
        if (history.isEmpty()) {
            throw LlmServerException("On-device provider requires at least one message")
        }
        val (initialHistory, lastMessage) = splitLastTurn(history)

        val conv = withContext(Dispatchers.IO) {
            ensureConversation(initialHistory, tools, maxTokens)
        }

        val response = try {
            withTimeout(INFERENCE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    // ChatEngine's tool loop resumes with a TOOL-role last turn, not a fresh USER
                    // question — sending the correct turn (not the original USER text) is what
                    // keeps multi-round tool calling working (#264 Task 6).
                    when (lastMessage.role) {
                        BridgedRole.USER -> conv.sendMessage(sanitizeForLiteRt(lastMessage.text).orEmpty())
                        else -> conv.sendMessage(lastMessage.toLiteRtMessage())
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw LlmTimeoutException("On-device inference timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw mapEngineException(e)
        } finally {
            idleRelease.schedule()
        }

        val bridged = BridgedAssistantMessage(
            text = response.toString(),
            toolCalls = response.toolCalls.mapIndexed { index, call ->
                BridgedToolCall(
                    id = "call_$index",
                    name = call.name,
                    argumentsJson = toolCallArgumentsToJson(call.arguments),
                )
            },
        )
        bridgedMessageToStreamFrames(bridged).forEach { emit(it) }
    }

    override fun isAvailable(): Boolean =
        modelRepository.isReady(config.modelId) && modelRepository.hasAvx2Support()

    override fun modelInfo(): ModelInfo = ModelInfo(name = config.modelId, isLocal = true)

    override fun close() {
        idleRelease.cancel()
        releaseEngine()
        scope.cancel()
    }

    private suspend fun ensureConversation(
        priorHistory: List<BridgedHistoryMessage>,
        tools: List<ToolDescriptor>,
        maxTokens: Int?,
    ): Conversation {
        val modelPath = modelRepository.modelPath(config.modelId)
            ?: throw LlmServerException("On-device model not downloaded: ${config.modelId}")

        val requestedContext = resolveOnDeviceContextTokens(config.modelId, config.contextTokens)
        if (loadedModelId != config.modelId || loadedContextTokens != requestedContext || engine == null) {
            swapEngine(modelPath, requestedContext)
        }
        val currentEngine = engine ?: throw LlmServerException("On-device engine failed to initialize")

        val systemText = sanitizeForLiteRt(
            priorHistory.filter { it.role == BridgedRole.SYSTEM }.joinToString("\n") { it.text }
        )
        val initialMessages = priorHistory
            .filterNot { it.role == BridgedRole.SYSTEM }
            .map { it.toLiteRtMessage() }
        val toolProviders = tools.map { tool(SchemaOnlyOpenApiTool(it)) }

        val conversationConfig = ConversationConfig(
            systemInstruction = systemText?.takeIf { it.isNotEmpty() }?.let { Contents.of(it) },
            initialMessages = initialMessages,
            tools = toolProviders,
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
            // PR1: ChatEngine owns the tool loop — see class doc "Spike" note.
            automaticToolCalling = false,
            maxOutputToken = maxTokens,
        )

        val previous = conversation
        conversation = null
        runCatching { previous?.close() }
        val next = currentEngine.createConversation(conversationConfig)
        conversation = next
        _engineState.value = LocalEngineState.Ready
        return next
    }

    private suspend fun swapEngine(modelPath: String, requestedContext: Int) {
        val hadExisting = engine != null
        releaseEngine()
        _engineState.value = LocalEngineState.Loading
        if (hadExisting) {
            // Give the previous model's GPU buffers a beat to drain before allocating a new
            // ~GB working set (Kai pattern) — otherwise both may briefly stay resident.
            System.gc()
            delay(GPU_DRAIN_MS)
        }
        try {
            val cacheDir = File(modelPath).parentFile?.absolutePath
            fun initWithBackend(backend: Backend, maxNumTokens: Int?): Engine {
                val instance = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        cacheDir = cacheDir,
                        maxNumTokens = maxNumTokens,
                    )
                )
                instance.initialize()
                return instance
            }

            fun initGpuThenCpu(maxNumTokens: Int?): Engine {
                return try {
                    initWithBackend(Backend.GPU(), maxNumTokens).also {
                        // Only set once GPU init actually succeeds — never leave a stale `true`
                        // stuck on a CPU-only engine from an earlier GPU init (#264 Task 6 Important #5).
                        ExperimentalFlags.enableSpeculativeDecoding = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ExperimentalFlags.enableSpeculativeDecoding = false
                    initWithBackend(Backend.CPU(), maxNumTokens)
                }
            }

            val catalogDefault = LOCAL_MODEL_CATALOG.find { it.id == config.modelId }?.defaultContextTokens
                ?: 4_096
            // Prefer requested size; fall back to catalog default, then engine default (null).
            // On success always record the *requested* context (Kai pattern) so we do not
            // thrash re-init when LiteRT accepted a smaller window; user preference stays put
            // for a later retry after restart / model swap.
            val newEngine = try {
                initGpuThenCpu(requestedContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestedContext != catalogDefault) {
                    try {
                        initGpuThenCpu(catalogDefault)
                    } catch (e2: CancellationException) {
                        throw e2
                    } catch (_: Exception) {
                        initGpuThenCpu(null)
                    }
                } else {
                    try {
                        initGpuThenCpu(null)
                    } catch (e2: CancellationException) {
                        throw e2
                    } catch (_: Exception) {
                        throw e
                    }
                }
            }
            engine = newEngine
            loadedModelId = config.modelId
            loadedContextTokens = requestedContext
        } catch (e: CancellationException) {
            _engineState.value = LocalEngineState.Error
            throw e
        } catch (e: Exception) {
            _engineState.value = LocalEngineState.Error
            throw mapEngineException(e)
        }
    }

    private fun releaseEngine() {
        val conv = conversation
        val eng = engine
        conversation = null
        engine = null
        loadedModelId = null
        loadedContextTokens = null
        runCatching { conv?.close() }
        runCatching { eng?.close() }
        _engineState.value = LocalEngineState.Uninitialized
    }

    private fun BridgedHistoryMessage.toLiteRtMessage(): LiteRtMessage {
        val safeText = sanitizeForLiteRt(text).orEmpty()
        return when (role) {
            BridgedRole.SYSTEM -> LiteRtMessage.system(safeText)
            BridgedRole.USER -> LiteRtMessage.user(safeText)
            BridgedRole.TOOL -> LiteRtMessage.tool(Contents.of(safeText))
            BridgedRole.ASSISTANT -> LiteRtMessage.model(
                contents = Contents.of(safeText),
                toolCalls = toolCalls.map {
                    LiteRtToolCall(name = it.name, arguments = jsonArgumentsToMap(it.argumentsJson))
                },
            )
        }
    }

    private fun mapEngineException(e: Throwable): Throwable = when (e) {
        is LlmTimeoutException, is LlmServerException -> e
        is TimeoutCancellationException -> LlmTimeoutException("On-device inference timed out")
        else -> LlmServerException("On-device inference failed: ${e.message ?: e::class.simpleName}")
    }

    /**
     * Schema-only [OpenApiTool] adapter: registers [descriptor] so LiteRT's grammar can emit
     * structured `toolCalls`, but never actually runs Jane tools inside LiteRT (see class doc).
     */
    private class SchemaOnlyOpenApiTool(private val descriptor: ToolDescriptor) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = descriptor.toOpenApiSchemaJson()

        override fun execute(paramsJsonString: String): String =
            throw IllegalStateException(
                "SchemaOnlyOpenApiTool.execute() called for '${descriptor.name}' with " +
                    "automaticToolCalling=false — LiteRT should only report toolCalls, not execute them"
            )
    }

    companion object {
        private const val GPU_DRAIN_MS = 750L
        private const val INFERENCE_TIMEOUT_MS = 120_000L
    }
}

actual object LocalLlmProviderFactory {
    actual fun create(
        config: LlmProviderConfig.OnDevice,
        modelRepository: ILocalModelRepository,
    ): LlmProvider = LocalLlmProvider(config, modelRepository)
}
