package io.github.leogallego.ansiblejane.assistant.engine

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import io.github.leogallego.ansiblejane.assistant.llm.LlmAuthException
import io.github.leogallego.ansiblejane.assistant.llm.LlmProvider
import io.github.leogallego.ansiblejane.assistant.llm.LlmRateLimitException
import io.github.leogallego.ansiblejane.assistant.llm.LlmServerException
import io.github.leogallego.ansiblejane.assistant.llm.LlmTimeoutException
import io.github.leogallego.ansiblejane.assistant.engine.DebugLog as Log
import io.github.leogallego.ansiblejane.assistant.tools.ToolSpec
import io.github.leogallego.ansiblejane.assistant.tools.toToolDescriptor
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.io.IOException

sealed interface ChatEvent {
    data class TextDelta(val text: String) : ChatEvent
    data class ToolExecuting(val toolName: String, val args: JsonObject) : ChatEvent
    data class ToolResult(val toolName: String, val result: io.github.leogallego.ansiblejane.assistant.tools.ToolResult) : ChatEvent
    data class ConfirmationRequired(
        val toolName: String,
        val args: JsonObject,
        val description: String
    ) : ChatEvent
    data class AssistantMessage(val fullText: String, val toolCallCount: Int) : ChatEvent
    data class Error(val message: String, val cause: Throwable? = null) : ChatEvent
    data class TokenUsageReport(val usage: TokenUsage) : ChatEvent
}

class ChatEngine(
    private val provider: LlmProvider,
    private val toolExecutor: ToolExecutor,
    private val maxIterations: Int = 10
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun processMessage(
        userMessage: String,
        history: List<ChatMessage>,
        tools: List<ToolSpec>,
        maxTokens: Int? = null,
        contextChars: Int = DEFAULT_CONTEXT_CHARS,
        onConfirmationRequired: (suspend (toolName: String, description: String, args: JsonObject) -> Boolean)? = null
    ): Flow<ChatEvent> = flow {
        var accInputTokens = 0
        var accOutputTokens = 0
        var accTotalTokens = 0
        var anyRealUsage = false
        val messages = mutableListOf<ChatMessage>()

        try {
            messages.add(ChatMessage(role = Role.SYSTEM, content = SYSTEM_PROMPT))
            history.filter { it.role == Role.USER || it.role == Role.ASSISTANT }
                .forEach { messages.add(it) }

            // #439: stable alphabetical order so LLM KV/prefix caches can reuse tool schemas
            val toolDescriptors = tools
                .sortedBy { it.name }
                .map { it.toToolDescriptor() }
            val toolSchemaChars = toolDescriptors.sumOf {
                it.name.length + it.description.length +
                    it.requiredParameters.sumOf { p -> p.name.length + p.description.length + 20 } +
                    it.optionalParameters.sumOf { p -> p.name.length + p.description.length + 20 }
            }
            val msgChars = messages.sumOf { it.content.length }
            Log.d(TAG, "PAYLOAD: ${tools.size} tools (~${toolSchemaChars} schema chars), " +
                "${messages.size} messages (~${msgChars} msg chars), total ~${toolSchemaChars + msgChars} chars")
            Log.d(TAG, "PAYLOAD tools: ${tools.map { it.name }}")
            var iterations = 0
            var totalToolCalls = 0
            val toolCallHistory = mutableListOf<List<String>>()
            val softLimit = (contextChars * 0.9).toInt()

            loop@ while (iterations < maxIterations) {
                iterations++
                coroutineContext.ensureActive()
                val textBuilder = StringBuilder()
                val pendingToolCalls = mutableMapOf<String, MutableToolCall>()
                var syntheticIdCounter = 0

                compactHistory(messages, softLimit)
                trimMessages(messages, contextChars)
                val prompt = buildPrompt(messages)

                provider.generateStream(prompt, toolDescriptors, maxTokens).collect { frame ->
                    Log.d(TAG, "FRAME: ${frame::class.simpleName} " +
                        when (frame) {
                            is StreamFrame.TextDelta -> "text=${frame.text.take(50)}"
                            is StreamFrame.ToolCallDelta -> "id=${frame.id} name=${frame.name} content=${frame.content?.take(50)}"
                            is StreamFrame.ToolCallComplete -> "id=${frame.id} name=${frame.name} content=${frame.content.take(50)}"
                            is StreamFrame.End -> ""
                            else -> frame.toString().take(80)
                        }
                    )
                    when (frame) {
                        is StreamFrame.TextDelta -> {
                            textBuilder.append(frame.text)
                            emit(ChatEvent.TextDelta(frame.text))
                        }
                        is StreamFrame.ToolCallDelta -> {
                            val id = frame.id ?: "tool_${syntheticIdCounter}"
                            val tc = pendingToolCalls.getOrPut(id) { MutableToolCall(id) }
                            if (frame.name != null) tc.name = frame.name
                            if (frame.content != null) tc.args.append(frame.content)
                        }
                        is StreamFrame.ToolCallComplete -> {
                            val id = frame.id ?: "tool_${syntheticIdCounter++}"
                            val tc = pendingToolCalls.getOrPut(id) { MutableToolCall(id) }
                            tc.name = frame.name
                            tc.args.clear()
                            tc.args.append(frame.content)
                        }
                        is StreamFrame.End -> {
                            val meta = frame.metaInfo
                            if (meta.totalTokensCount != null) {
                                accInputTokens += meta.inputTokensCount ?: 0
                                accOutputTokens += meta.outputTokensCount ?: 0
                                accTotalTokens += meta.totalTokensCount ?: 0
                                anyRealUsage = true
                            }
                        }
                        else -> { /* ReasoningDelta etc — ignore */ }
                    }
                }

                val completedCalls = pendingToolCalls.values
                    .filter { it.name != null }
                    .map { tc ->
                        MessagePart.Tool.Call(
                            id = tc.id,
                            tool = tc.name!!,
                            args = tc.args.toString().ifEmpty { "{}" }
                        )
                    }

                val responseText = textBuilder.toString().ifEmpty { null }

                if (completedCalls.isNotEmpty() && iterations < maxIterations) {
                    Log.d(TAG, "ITER $iterations: ${completedCalls.size} tool calls: " +
                        "${completedCalls.map { it.tool }}")
                    val currentSignature = completedCalls.map {
                        "${it.tool}:${it.args.hashCode()}"
                    }
                    toolCallHistory.add(currentSignature)

                    if (isRepeatingToolCalls(toolCallHistory)) {
                        Log.w(TAG, "ITER $iterations: repeat detected, stopping")
                        val usage = buildTokenUsage(anyRealUsage, accInputTokens, accOutputTokens, accTotalTokens, messages, responseText)
                        emit(ChatEvent.TokenUsageReport(usage))
                        emit(ChatEvent.AssistantMessage(
                            (responseText ?: "") + "\n\nStopped: the same tools were being called repeatedly.",
                            totalToolCalls
                        ))
                        return@flow
                    }

                    val toolCallsJson = json.encodeToString(
                        JsonArray.serializer(),
                        JsonArray(completedCalls.map { tc ->
                            JsonObject(mapOf(
                                "id" to JsonPrimitive(tc.id),
                                "name" to JsonPrimitive(tc.tool),
                                "arguments" to JsonPrimitive(tc.args)
                            ))
                        })
                    )
                    messages.add(ChatMessage(
                        role = Role.ASSISTANT,
                        content = responseText ?: "",
                        toolCallsJson = toolCallsJson
                    ))

                    val toolSummaries = mutableListOf<String>()
                    for (toolCall in completedCalls) {
                        val argsJson = try {
                            json.parseToJsonElement(toolCall.args).jsonObject
                        } catch (_: Exception) {
                            JsonObject(emptyMap())
                        }
                        emit(ChatEvent.ToolExecuting(toolCall.tool, argsJson))

                        val tool = toolExecutor.findTool(toolCall.tool)
                        val toolResult = if (tool != null && tool.isDestructive && onConfirmationRequired != null) {
                            val description = descriptionForConfirmation(toolCall.tool, argsJson)
                            emit(ChatEvent.ConfirmationRequired(toolCall.tool, argsJson, description))
                            val approved = onConfirmationRequired(toolCall.tool, description, argsJson)
                            if (approved) {
                                toolExecutor.execute(toolCall)
                            } else {
                                io.github.leogallego.ansiblejane.assistant.tools.ToolResult(
                                    success = false,
                                    data = "User declined the action"
                                )
                            }
                        } else {
                            toolExecutor.execute(toolCall)
                        }
                        totalToolCalls++

                        emit(ChatEvent.ToolResult(toolCall.tool, toolResult))

                        messages.add(ChatMessage(
                            role = Role.TOOL,
                            content = toolResult.data
                                ?: "Tool execution failed: ${toolResult.errorType ?: "unknown error"}",
                            toolCallId = toolCall.id,
                            toolName = toolCall.tool
                        ))
                        toolSummaries.add("${toolCall.tool}: ${toolResult.data?.take(200) ?: "error"}")
                    }

                    if (iterations > 1) {
                        compactToolMessages(messages, toolSummaries)
                    }
                } else {
                    Log.d(TAG, "DONE: $iterations iterations, $totalToolCalls total tool calls, " +
                        "response=${responseText?.length ?: 0} chars")
                    val finalText = if (completedCalls.isNotEmpty() && iterations >= maxIterations) {
                        (responseText ?: "") + "\n\nI wasn't able to complete this request within the tool call limit."
                    } else {
                        responseText ?: textBuilder.toString()
                    }
                    val usage = buildTokenUsage(anyRealUsage, accInputTokens, accOutputTokens, accTotalTokens, messages, finalText)
                    emit(ChatEvent.TokenUsageReport(usage))
                    emit(ChatEvent.AssistantMessage(finalText, totalToolCalls))
                    return@flow
                }
            }

            val usage = buildTokenUsage(anyRealUsage, accInputTokens, accOutputTokens, accTotalTokens, messages, null)
            emit(ChatEvent.TokenUsageReport(usage))
            emit(ChatEvent.AssistantMessage(
                "I wasn't able to complete this request within the tool call limit.",
                totalToolCalls
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmAuthException) {
            Log.e(TAG, "AUTH error: ${e.message}", e)
            emitPartialTokenUsage(anyRealUsage, accInputTokens, accOutputTokens, accTotalTokens, messages)
            emit(ChatEvent.Error(e.message ?: "Authentication failed — check API key", e))
        } catch (e: LlmRateLimitException) {
            Log.w(TAG, "RATE_LIMIT: ${e.message}")
            emitPartialTokenUsage(anyRealUsage, accInputTokens, accOutputTokens, accTotalTokens, messages)
            emit(ChatEvent.Error(e.message ?: "Rate limited — try again later", e))
        } catch (e: LlmTimeoutException) {
            Log.w(TAG, "TIMEOUT: ${e.message}")
            emitPartialTokenUsage(anyRealUsage, accInputTokens, accOutputTokens, accTotalTokens, messages)
            emit(ChatEvent.Error("Response timed out", e))
        } catch (e: IOException) {
            Log.e(TAG, "IO error: ${e.message}", e)
            emitPartialTokenUsage(anyRealUsage, accInputTokens, accOutputTokens, accTotalTokens, messages)
            emit(ChatEvent.Error("Unable to reach LLM server: ${e.message}", e))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ${e.message}", e)
            emitPartialTokenUsage(anyRealUsage, accInputTokens, accOutputTokens, accTotalTokens, messages)
            emit(ChatEvent.Error("Error: ${e.message}", e))
        }
    }

    private fun buildPrompt(messages: List<ChatMessage>): Prompt {
        val koogMessages = messages.flatMap { msg ->
            when (msg.role) {
                Role.SYSTEM -> listOf(Message.System(
                    content = msg.content,
                    metaInfo = RequestMetaInfo.Empty
                ))
                Role.USER -> listOf(Message.User(
                    content = msg.content,
                    metaInfo = RequestMetaInfo.Empty
                ))
                Role.ASSISTANT -> {
                    val json = msg.toolCallsJson
                    if (json != null) {
                        val toolCalls = parseToolCalls(json)
                        val parts = mutableListOf<MessagePart.ResponsePart>()
                        if (msg.content.isNotEmpty()) {
                            parts.add(MessagePart.Text(msg.content))
                        }
                        parts.addAll(toolCalls)
                        listOf(Message.Assistant(
                            parts = parts,
                            metaInfo = ResponseMetaInfo.Empty
                        ))
                    } else {
                        listOf(Message.Assistant(
                            content = msg.content,
                            metaInfo = ResponseMetaInfo.Empty
                        ))
                    }
                }
                Role.TOOL -> listOf(Message.User(
                    part = MessagePart.Tool.Result(
                        id = msg.toolCallId,
                        tool = msg.toolName ?: msg.toolCallId ?: "",
                        output = msg.content
                    ),
                    metaInfo = RequestMetaInfo.Empty
                ))
            }
        }
        return Prompt(messages = koogMessages, id = "chat")
    }

    private fun parseToolCalls(toolCallsJson: String): List<MessagePart.Tool.Call> {
        return try {
            val element = json.parseToJsonElement(toolCallsJson)
            if (element is JsonArray) {
                element.map { el ->
                    val obj = el.jsonObject
                    MessagePart.Tool.Call(
                        id = obj["id"]?.jsonPrimitive?.content,
                        tool = obj["name"]?.jsonPrimitive?.content ?: "",
                        args = obj["arguments"]?.jsonPrimitive?.content ?: "{}"
                    )
                }
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun compactToolMessages(
        messages: MutableList<ChatMessage>,
        currentSummaries: List<String>
    ) {
        val keepFrom = messages.indexOfLast { it.role == Role.USER } + 1
        val toCompact = mutableListOf<Int>()
        for (i in keepFrom until messages.size - currentSummaries.size) {
            if (messages[i].role == Role.TOOL || (messages[i].role == Role.ASSISTANT && messages[i].toolCallsJson != null)) {
                toCompact.add(i)
            }
        }
        if (toCompact.isEmpty()) return
        val summary = toCompact
            .filter { messages[it].role == Role.TOOL }
            .joinToString("; ") { i ->
                val msg = messages[i]
                val preview = msg.content.take(100).replace("\n", " ")
                "tool_result: $preview"
            }
        for (i in toCompact.reversed()) {
            messages.removeAt(i)
        }
        if (summary.isNotBlank()) {
            messages.add(keepFrom, ChatMessage(role = Role.ASSISTANT, content = "[Previous tool calls: $summary]"))
        }
    }

    private fun isRepeatingToolCalls(history: List<List<String>>): Boolean {
        if (history.size < 3) return false
        val last = history.last()
        return history[history.size - 2] == last && history[history.size - 3] == last
    }

    private fun compactHistory(
        messages: MutableList<ChatMessage>,
        softLimit: Int
    ) {
        val totalChars = messages.sumOf { it.content.length }
        if (totalChars <= softLimit) return

        val systemCount = messages.takeWhile { it.role == Role.SYSTEM }.size
        val keepTail = 4
        if (messages.size <= systemCount + keepTail) return

        val compactEnd = messages.size - keepTail
        val toCompact = messages.subList(systemCount, compactEnd)
        if (toCompact.isEmpty()) return

        Log.d(TAG, "COMPACT: $totalChars chars > $softLimit soft limit, " +
            "compacting ${toCompact.size} messages (keeping last $keepTail)")

        val summaries = mutableListOf<String>()
        var i = 0
        while (i < toCompact.size) {
            val msg = toCompact[i]
            when (msg.role) {
                Role.USER -> {
                    val topic = msg.content.take(40).replace("\n", " ").trim()
                    val nextAssistant = toCompact.getOrNull(i + 1)
                    val result = if (nextAssistant?.role == Role.ASSISTANT && nextAssistant.toolCallsJson == null) {
                        nextAssistant.content.take(40).replace("\n", " ").trim()
                        .let { if (it.length >= 40) "${it}…" else it }
                    } else null
                    summaries.add(if (result != null) "$topic ($result)" else topic)
                    i++
                }
                Role.TOOL, Role.ASSISTANT -> i++
                else -> i++
            }
        }

        toCompact.clear()
        if (summaries.isNotEmpty()) {
            val digest = "[Earlier: ${summaries.joinToString("; ")}]"
            messages.add(systemCount, ChatMessage(role = Role.ASSISTANT, content = digest))
            Log.d(TAG, "COMPACT: ${summaries.size} exchanges → ${digest.length} chars")
        }
    }

    private fun trimMessages(
        messages: MutableList<ChatMessage>,
        maxChars: Int = DEFAULT_CONTEXT_CHARS
    ) {
        val totalChars = messages.sumOf { it.content.length }
        if (totalChars <= maxChars) return
        Log.d(TAG, "TRIM: $totalChars chars > $maxChars limit, trimming ${messages.size} messages")

        val systemMessages = messages.takeWhile { it.role == Role.SYSTEM }
        val systemChars = systemMessages.sumOf { it.content.length }
        val available = maxChars - systemChars

        var kept = 0
        var keepFrom = messages.size
        for (i in messages.indices.reversed()) {
            if (messages[i].role == Role.SYSTEM) break
            kept += messages[i].content.length
            if (kept > available) break
            keepFrom = i
        }

        if (keepFrom > systemMessages.size) {
            messages.subList(systemMessages.size, keepFrom).clear()
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ChatEvent>.emitPartialTokenUsage(
        anyRealUsage: Boolean,
        accInputTokens: Int,
        accOutputTokens: Int,
        accTotalTokens: Int,
        messages: List<ChatMessage>
    ) {
        if (anyRealUsage) {
            emit(ChatEvent.TokenUsageReport(TokenUsage(
                inputTokens = accInputTokens,
                outputTokens = accOutputTokens,
                totalTokens = accTotalTokens
            )))
        }
    }

    private fun buildTokenUsage(
        anyRealUsage: Boolean,
        accInputTokens: Int,
        accOutputTokens: Int,
        accTotalTokens: Int,
        messages: List<ChatMessage>,
        responseText: String?
    ): TokenUsage {
        return if (anyRealUsage) {
            TokenUsage(
                inputTokens = accInputTokens,
                outputTokens = accOutputTokens,
                totalTokens = accTotalTokens
            )
        } else {
            val totalChars = messages.sumOf { it.content.length } + (responseText?.length ?: 0)
            val estimated = totalChars / 4
            TokenUsage(
                inputTokens = estimated * 2 / 3,
                outputTokens = estimated / 3,
                totalTokens = estimated,
                isEstimated = true
            )
        }
    }

    private fun descriptionForConfirmation(toolName: String, args: JsonObject): String {
        return when (toolName) {
            "approve_workflow" -> "Approve workflow approval step #${args["approval_id"]?.jsonPrimitive?.content ?: "?"}"
            "deny_workflow" -> "Deny workflow approval step #${args["approval_id"]?.jsonPrimitive?.content ?: "?"}"
            "launch_job" -> "Launch job template #${args["template_id"]?.jsonPrimitive?.content ?: "?"}"
            "launch_workflow" -> "Launch workflow template #${args["template_id"]?.jsonPrimitive?.content ?: "?"}"
            "toggle_schedule" -> "Toggle schedule #${args["schedule_id"]?.jsonPrimitive?.content ?: "?"}"
            else -> "Execute $toolName"
        }
    }

    private class MutableToolCall(val id: String) {
        var name: String? = null
        val args = StringBuilder()
    }

    companion object {
        private const val TAG = "ChatEngine"
        private const val DEFAULT_CONTEXT_CHARS = 16_000
        const val SYSTEM_PROMPT = """You are a concise AI assistant for Ansible Automation Platform (AAP). Rules:
- NEVER fabricate, invent, or guess data. Only present information returned by tool calls. If a tool call fails, report the error — do not make up results. If you have no tool to answer a question, say so clearly.
- You have local tools (list_jobs, launch_job, etc.) that connect directly to the AAP instance and MCP tools (hosts_list, jobs_retrieve, etc.) for extended capabilities. Prefer local tools when available.
- When results contain more than 10 items, show a summary with total count and the 5 most recent. Ask before listing all.
- Use short structured formatting (bullets, bold labels). No lengthy prose.
- Never repeat raw tool output verbatim — summarize it.
- For write operations (launch, cancel, toggle), explain what you will do and wait for confirmation.
- For optional tool parameters you don't need, omit them entirely — do not pass null or empty values."""
    }
}
