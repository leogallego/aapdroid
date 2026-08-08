package io.github.leogallego.ansiblejane.assistant.llm

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD coverage for the LiteRT-free bridge (#264 Task 6). No LiteRT `Engine`/`Conversation` is
 * loaded here — [BridgedAssistantMessage] stands in for what the androidMain/jvmMain actuals
 * build from a real LiteRT `Message` response.
 */
class LiteRtStreamFrameBridgeTest {

    // --- bridgedMessageToStreamFrames -----------------------------------------------------

    @Test
    fun `text-only message emits TextDelta then End with stop`() {
        val frames = bridgedMessageToStreamFrames(BridgedAssistantMessage(text = "Hello there"))

        assertEquals(2, frames.size)
        assertEquals("Hello there", (frames[0] as StreamFrame.TextDelta).text)
        val end = frames[1] as StreamFrame.End
        assertEquals("stop", end.finishReason)
    }

    @Test
    fun `tool-only message emits ToolCallComplete then End with tool_calls`() {
        val frames = bridgedMessageToStreamFrames(
            BridgedAssistantMessage(
                text = null,
                toolCalls = listOf(BridgedToolCall(id = "call_0", name = "list_jobs", argumentsJson = "{}")),
            )
        )

        assertEquals(2, frames.size)
        val toolCall = frames[0] as StreamFrame.ToolCallComplete
        assertEquals("call_0", toolCall.id)
        assertEquals("list_jobs", toolCall.name)
        assertEquals("{}", toolCall.content)
        assertEquals("tool_calls", (frames[1] as StreamFrame.End).finishReason)
    }

    @Test
    fun `text and tool calls emit TextDelta, one ToolCallComplete per call, then End`() {
        val frames = bridgedMessageToStreamFrames(
            BridgedAssistantMessage(
                text = "Let me check that.",
                toolCalls = listOf(
                    BridgedToolCall(id = "call_0", name = "list_jobs", argumentsJson = """{"status":"failed"}"""),
                    BridgedToolCall(id = "call_1", name = "ping", argumentsJson = "{}"),
                ),
            )
        )

        assertEquals(4, frames.size)
        assertTrue(frames[0] is StreamFrame.TextDelta)
        assertEquals("call_0", (frames[1] as StreamFrame.ToolCallComplete).id)
        assertEquals("call_1", (frames[2] as StreamFrame.ToolCallComplete).id)
        assertEquals("tool_calls", (frames[3] as StreamFrame.End).finishReason)
    }

    @Test
    fun `empty message emits only End with stop`() {
        val frames = bridgedMessageToStreamFrames(BridgedAssistantMessage(text = null, toolCalls = emptyList()))

        assertEquals(1, frames.size)
        assertEquals("stop", (frames[0] as StreamFrame.End).finishReason)
    }

    @Test
    fun `blank text is treated as no text frame`() {
        val frames = bridgedMessageToStreamFrames(BridgedAssistantMessage(text = ""))

        assertEquals(1, frames.size)
        assertTrue(frames[0] is StreamFrame.End)
    }

    // --- sanitizeForLiteRt -----------------------------------------------------------------

    @Test
    fun `sanitizeForLiteRt passes through clean text unchanged`() {
        assertEquals("hello world", sanitizeForLiteRt("hello world"))
    }

    @Test
    fun `sanitizeForLiteRt strips lone UTF-16 surrogates`() {
        val loneSurrogate = "before${0xD800.toChar()}after"
        assertEquals("beforeafter", sanitizeForLiteRt(loneSurrogate))
    }

    @Test
    fun `sanitizeForLiteRt keeps valid surrogate pairs (emoji)`() {
        val withEmoji = "hi \uD83D\uDE00 there"
        assertEquals(withEmoji, sanitizeForLiteRt(withEmoji))
    }

    @Test
    fun `sanitizeForLiteRt passes through null`() {
        assertNull(sanitizeForLiteRt(null))
    }

    // --- promptToBridgedHistory --------------------------------------------------------------

    @Test
    fun `promptToBridgedHistory maps system, user, assistant and tool-result turns`() {
        val prompt = Prompt(
            messages = listOf(
                Message.System(content = "You are Jane", metaInfo = RequestMetaInfo.Empty),
                Message.User(content = "List failed jobs", metaInfo = RequestMetaInfo.Empty),
                Message.Assistant(
                    parts = listOf(MessagePart.Tool.Call(id = "call_1", tool = "list_jobs", args = """{"status":"failed"}""")),
                    metaInfo = ResponseMetaInfo.Empty,
                ),
                Message.User(
                    part = MessagePart.Tool.Result(id = "call_1", tool = "list_jobs", output = "[]"),
                    metaInfo = RequestMetaInfo.Empty,
                ),
            ),
            id = "test",
        )

        val history = promptToBridgedHistory(prompt)

        assertEquals(4, history.size)
        assertEquals(BridgedRole.SYSTEM, history[0].role)
        assertEquals("You are Jane", history[0].text)
        assertEquals(BridgedRole.USER, history[1].role)
        assertEquals("List failed jobs", history[1].text)
        assertEquals(BridgedRole.ASSISTANT, history[2].role)
        assertEquals(1, history[2].toolCalls.size)
        assertEquals("list_jobs", history[2].toolCalls[0].name)
        assertEquals(BridgedRole.TOOL, history[3].role)
        assertEquals("[]", history[3].text)
    }

    // --- splitLastTurn (#264 Task 6: tool-loop history must not be discarded) --------------

    @Test
    fun `splitLastTurn keeps assistant tool-call and prior turns, last is TOOL`() {
        val prompt = Prompt(
            messages = listOf(
                Message.System(content = "You are Jane", metaInfo = RequestMetaInfo.Empty),
                Message.User(content = "List failed jobs", metaInfo = RequestMetaInfo.Empty),
                Message.Assistant(
                    parts = listOf(MessagePart.Tool.Call(id = "call_1", tool = "list_jobs", args = """{"status":"failed"}""")),
                    metaInfo = ResponseMetaInfo.Empty,
                ),
                Message.User(
                    part = MessagePart.Tool.Result(id = "call_1", tool = "list_jobs", output = "[]"),
                    metaInfo = RequestMetaInfo.Empty,
                ),
            ),
            id = "test",
        )
        val history = promptToBridgedHistory(prompt)

        val split = splitLastTurn(history)

        assertEquals(3, split.initialHistory.size)
        assertEquals(BridgedRole.SYSTEM, split.initialHistory[0].role)
        assertEquals(BridgedRole.USER, split.initialHistory[1].role)
        assertEquals(BridgedRole.ASSISTANT, split.initialHistory[2].role)
        assertEquals(1, split.initialHistory[2].toolCalls.size)
        assertEquals(BridgedRole.TOOL, split.lastMessage.role)
        assertEquals("[]", split.lastMessage.text)
    }

    @Test
    fun `splitLastTurn on a single user message returns empty initialHistory`() {
        val history = listOf(BridgedHistoryMessage(BridgedRole.USER, "hello"))

        val split = splitLastTurn(history)

        assertTrue(split.initialHistory.isEmpty())
        assertEquals(BridgedRole.USER, split.lastMessage.role)
        assertEquals("hello", split.lastMessage.text)
    }

    @Test
    fun `splitLastTurn throws on empty history`() {
        var threw = false
        try {
            splitLastTurn(emptyList())
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    // --- tool call argument JSON round-trip -------------------------------------------------

    @Test
    fun `toolCallArgumentsToJson serializes mixed value types`() {
        val json = toolCallArgumentsToJson(
            mapOf("status" to "failed", "limit" to 10, "verbose" to true, "note" to null)
        )

        assertTrue(json.contains(""""status":"failed""""))
        assertTrue(json.contains(""""limit":10"""))
        assertTrue(json.contains(""""verbose":true"""))
        assertTrue(json.contains(""""note":null"""))
    }

    @Test
    fun `jsonArgumentsToMap parses back what toolCallArgumentsToJson produced`() {
        val json = toolCallArgumentsToJson(mapOf("status" to "failed", "limit" to 10))

        val map = jsonArgumentsToMap(json)

        assertEquals("failed", map["status"])
        assertEquals(10L, map["limit"])
    }

    @Test
    fun `jsonArgumentsToMap returns empty map on malformed JSON`() {
        assertEquals(emptyMap(), jsonArgumentsToMap("not json"))
    }

    // --- OpenAPI schema JSON for schema-only tool registration ------------------------------

    @Test
    fun `toOpenApiSchemaJson includes name, required and optional params`() {
        val descriptor = ToolDescriptor(
            name = "list_jobs",
            description = "List AAP jobs",
            requiredParameters = listOf(
                ToolParameterDescriptor(name = "status", description = "Job status", type = ToolParameterType.String)
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(name = "limit", description = "Max results", type = ToolParameterType.Integer)
            ),
        )

        val json = descriptor.toOpenApiSchemaJson()

        assertTrue(json.contains(""""name":"list_jobs""""))
        assertTrue(json.contains(""""status""""))
        assertTrue(json.contains(""""limit""""))
        assertTrue(json.contains(""""required":["status"]"""))
        assertFalse(json.contains("\"required\":[\"status\",\"limit\"]"))
    }
}
