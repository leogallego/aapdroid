package io.github.leogallego.ansiblejane.assistant.llm

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * LiteRT-free bridging helpers for [LocalLlmProvider] (#264 PR1).
 *
 * These operate on plain Kotlin data / Koog types only — no `com.google.ai.edge.litertlm.*`
 * imports — so they can live in `commonMain` and be unit-tested in `jvmTest` without loading
 * the native LiteRT engine (see `LiteRtStreamFrameBridgeTest`). The androidMain/jvmMain actuals
 * convert their real LiteRT `Message`/`ToolCall` results into [BridgedAssistantMessage] and call
 * [bridgedMessageToStreamFrames].
 */

/** One tool call as reported by LiteRT's `Message.toolCalls` (which has no call id — synthesized here). */
internal data class BridgedToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/** The synchronous result of a single LiteRT `Conversation.sendMessage()` call. */
internal data class BridgedAssistantMessage(
    val text: String?,
    val toolCalls: List<BridgedToolCall> = emptyList(),
)

/**
 * Synthesizes [StreamFrame]s for PR1's sync path: LiteRT returns one complete [Message] per
 * turn (no token streaming yet), so this emits at most one [StreamFrame.TextDelta], one
 * [StreamFrame.ToolCallComplete] per call, then [StreamFrame.End]. ChatEngine consumes this
 * identically to a real streaming provider.
 */
internal fun bridgedMessageToStreamFrames(message: BridgedAssistantMessage): List<StreamFrame> {
    val frames = mutableListOf<StreamFrame>()
    message.text?.takeIf { it.isNotEmpty() }?.let { frames += StreamFrame.TextDelta(it) }
    message.toolCalls.forEach { call ->
        frames += StreamFrame.ToolCallComplete(id = call.id, name = call.name, content = call.argumentsJson)
    }
    frames += StreamFrame.End(finishReason = if (message.toolCalls.isEmpty()) "stop" else "tool_calls")
    return frames
}

/**
 * Strips lone (unpaired) UTF-16 surrogates that crash LiteRT's JNI/UTF-8 boundary (Kai
 * `sanitizeForLiteRt`) while preserving valid surrogate pairs (e.g. emoji). Applied to every
 * string handed to the LiteRT engine (system instruction, history, user turn).
 */
fun sanitizeForLiteRt(value: String?): String? {
    if (value == null) return null
    if (value.none { it.isSurrogate() }) return value
    val builder = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val current = value[i]
        when {
            current.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                builder.append(current).append(value[i + 1])
                i += 2
            }
            current.isSurrogate() -> i += 1 // lone surrogate — drop it
            else -> {
                builder.append(current)
                i += 1
            }
        }
    }
    return builder.toString()
}

/** Role of a [BridgedHistoryMessage], independent of both Koog's and LiteRT's role enums. */
internal enum class BridgedRole { SYSTEM, USER, ASSISTANT, TOOL }

/** One turn of conversation history, LiteRT-free. */
internal data class BridgedHistoryMessage(
    val role: BridgedRole,
    val text: String,
    val toolCalls: List<BridgedToolCall> = emptyList(),
)

/**
 * Maps a Koog [Prompt] to [BridgedHistoryMessage]s. Tool results are carried as Koog
 * `Message.User(part = MessagePart.Tool.Result(...))` (see `ChatEngine.buildPrompt`) — bridged
 * to [BridgedRole.TOOL] here so LiteRT actuals can map them to `Message.tool(...)`.
 */
internal fun promptToBridgedHistory(prompt: Prompt): List<BridgedHistoryMessage> =
    prompt.messages.map { it.toBridgedHistoryMessage() }

private fun Message.toBridgedHistoryMessage(): BridgedHistoryMessage = when (this) {
    is Message.System -> BridgedHistoryMessage(BridgedRole.SYSTEM, textContent())
    is Message.User -> {
        val toolResult = parts.filterIsInstance<MessagePart.Tool.Result>().firstOrNull()
        if (toolResult != null) {
            BridgedHistoryMessage(BridgedRole.TOOL, toolResult.output)
        } else {
            BridgedHistoryMessage(BridgedRole.USER, textContent())
        }
    }
    is Message.Assistant -> {
        val calls = parts.filterIsInstance<MessagePart.Tool.Call>().map { call ->
            BridgedToolCall(id = call.id ?: call.tool, name = call.tool, argumentsJson = call.args)
        }
        BridgedHistoryMessage(BridgedRole.ASSISTANT, textContent(), calls)
    }
}

/**
 * Serializes a LiteRT `ToolCall.arguments` (`Map<String, Any?>`, values from the engine's own
 * JSON parsing) back into a JSON object string for [BridgedToolCall.argumentsJson] — matching
 * the `content` shape `ChatEngine`/`ToolExecutor` already expect from remote providers.
 */
internal fun toolCallArgumentsToJson(arguments: Map<String, Any?>): String =
    buildJsonObject { arguments.forEach { (key, value) -> put(key, value.toJsonElement()) } }.toString()

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Map<*, *> -> buildJsonObject { forEach { (key, value) -> put(key.toString(), value.toJsonElement()) } }
    is Iterable<*> -> buildJsonArray { forEach { add(it.toJsonElement()) } }
    else -> JsonPrimitive(this.toString())
}

/**
 * Builds a minimal OpenAPI-shaped tool description JSON for LiteRT's schema-only `OpenApiTool`
 * registration (`getToolDescriptionJsonString()`). PR1 never calls `OpenApiTool.execute()` —
 * `automaticToolCalling = false` means LiteRT only *emits* `toolCalls` for ChatEngine to run; see
 * the spike note on `LocalLlmProvider` for how this was confirmed against the 0.15.0 API surface.
 */
internal fun ToolDescriptor.toOpenApiSchemaJson(): String {
    val required = requiredParameters
    val allParams = required + optionalParameters
    val properties = buildJsonObject {
        allParams.forEach { param ->
            put(param.name, buildJsonObject {
                put("type", param.type.toJsonSchemaTypeName())
                put("description", param.description)
                (param.type as? ToolParameterType.Enum)?.let { enumType ->
                    put("enum", JsonArray(enumType.entries.map { JsonPrimitive(it) }))
                }
            })
        }
    }
    return buildJsonObject {
        put("name", name)
        put("description", description)
        put("parameters", buildJsonObject {
            put("type", "object")
            put("properties", properties)
            put("required", JsonArray(required.map { JsonPrimitive(it.name) }))
        })
    }.toString()
}

private fun ToolParameterType.toJsonSchemaTypeName(): String = when (this) {
    is ToolParameterType.Integer -> "integer"
    is ToolParameterType.Float -> "number"
    is ToolParameterType.Boolean -> "boolean"
    is ToolParameterType.List -> "array"
    else -> "string"
}

/**
 * Inverse of [toolCallArgumentsToJson] — reconstructs a `Map<String, Any?>` for LiteRT's
 * `ToolCall(name, arguments)` constructor when replaying a prior assistant turn's tool call back
 * into `initialMessages` history. Returns an empty map on malformed JSON rather than throwing —
 * history reconstruction should never abort a new turn.
 */
internal fun jsonArgumentsToMap(json: String): Map<String, Any?> = try {
    val element = Json.parseToJsonElement(json)
    if (element is JsonObject) element.mapValues { (_, v) -> v.toPlainValue() } else emptyMap()
} catch (_: Exception) {
    emptyMap()
}

private fun JsonElement.toPlainValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> mapValues { (_, v) -> v.toPlainValue() }
    is JsonArray -> map { it.toPlainValue() }
    else -> {
        val primitive = this as JsonPrimitive
        if (primitive.isString) {
            primitive.content
        } else {
            primitive.booleanOrNull ?: primitive.longOrNull ?: primitive.doubleOrNull ?: primitive.content
        }
    }
}
