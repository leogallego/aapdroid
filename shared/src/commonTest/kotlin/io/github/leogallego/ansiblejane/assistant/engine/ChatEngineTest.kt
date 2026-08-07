package io.github.leogallego.ansiblejane.assistant.engine

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.streaming.StreamFrame
import app.cash.turbine.test
import io.github.leogallego.ansiblejane.assistant.llm.LlmProvider
import io.github.leogallego.ansiblejane.assistant.llm.ModelInfo
import io.github.leogallego.ansiblejane.TestOnly
import io.github.leogallego.ansiblejane.assistant.tools.Tool
import io.github.leogallego.ansiblejane.assistant.tools.ToolResult
import io.github.leogallego.ansiblejane.assistant.tools.ToolStub
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(TestOnly::class)
class ChatEngineTest {

    @Test
    fun `single turn text response emits TextDelta and AssistantMessage`() = runTest {
        val provider = FakeLlmProvider(listOf(
            FakeResponse(text = "Hello there!")
        ))
        val executor = ToolExecutor(emptyList())
        val engine = ChatEngine(provider, executor)

        engine.processMessage("Hi", emptyList(), emptyList()).test {
            val delta = awaitItem()
            assertTrue(delta is ChatEvent.TextDelta)

            val tokenReport = awaitItem()
            assertTrue(tokenReport is ChatEvent.TokenUsageReport)

            val msg = awaitItem()
            assertTrue(msg is ChatEvent.AssistantMessage)
            assertEquals("Hello there!", (msg as ChatEvent.AssistantMessage).fullText)
            assertEquals(0, msg.toolCallCount)

            awaitComplete()
        }
    }

    @Test
    fun `tool calling loop executes tools and re-sends to LLM`() = runTest {
        val provider = FakeLlmProvider(listOf(
            FakeResponse(toolCalls = listOf(
                FakeToolCall("call_1", "list_jobs", """{"status":"failed"}""")
            )),
            FakeResponse(text = "Found 3 failed jobs.")
        ))
        val fakeTool = ToolStub(
            "list_jobs",
            ToolResult(success = true, data = "[{\"id\":1},{\"id\":2},{\"id\":3}]"),
            "List jobs"
        )
        val executor = ToolExecutor(listOf<Tool>(fakeTool))
        val engine = ChatEngine(provider, executor)

        engine.processMessage("Show failed jobs", emptyList(), emptyList()).test {
            val executing = awaitItem()
            assertTrue(executing is ChatEvent.ToolExecuting)
            assertEquals("list_jobs", (executing as ChatEvent.ToolExecuting).toolName)

            val toolResult = awaitItem()
            assertTrue(toolResult is ChatEvent.ToolResult)

            val delta = awaitItem()
            assertTrue(delta is ChatEvent.TextDelta)

            val tokenReport = awaitItem()
            assertTrue(tokenReport is ChatEvent.TokenUsageReport)

            val msg = awaitItem()
            assertTrue(msg is ChatEvent.AssistantMessage)
            assertEquals("Found 3 failed jobs.", (msg as ChatEvent.AssistantMessage).fullText)
            assertEquals(1, msg.toolCallCount)

            awaitComplete()
        }
    }

    @Test
    fun `max iteration limit stops infinite loops`() = runTest {
        val provider = FakeLlmProvider(
            (1..15).map {
                FakeResponse(toolCalls = listOf(
                    FakeToolCall("call_1", "loop_tool", "{}")
                ))
            }
        )
        val fakeTool = ToolStub(
            "loop_tool",
            ToolResult(success = true, data = "ok"),
            "Loops"
        )
        val executor = ToolExecutor(listOf<Tool>(fakeTool))
        val engine = ChatEngine(provider, executor, maxIterations = 3)

        engine.processMessage("loop", emptyList(), emptyList()).test {
            val events = mutableListOf<ChatEvent>()
            do {
                val event = awaitItem()
                events.add(event)
            } while (event !is ChatEvent.AssistantMessage)

            val lastEvent = events.last()
            assertTrue(lastEvent is ChatEvent.AssistantMessage)
            assertTrue(
                (lastEvent as ChatEvent.AssistantMessage).fullText
                    .contains("tool call limit")
            )

            awaitComplete()
        }
    }

    @Test
    fun `LLM error emits ChatEvent Error`() = runTest {
        val provider = FakeLlmProvider(error = RuntimeException("Connection refused"))
        val executor = ToolExecutor(emptyList())
        val engine = ChatEngine(provider, executor)

        engine.processMessage("test", emptyList(), emptyList()).test {
            val error = awaitItem()
            assertTrue(error is ChatEvent.Error)
            assertTrue((error as ChatEvent.Error).message.contains("Connection refused"))

            awaitComplete()
        }
    }

    @Test
    fun `toolDescriptors SHOULD be sorted by name for stable prompt caching`() = runTest {
        val provider = CapturingLlmProvider()
        val executor = ToolExecutor(emptyList())
        val engine = ChatEngine(provider, executor)
        val tools = listOf(
            io.github.leogallego.ansiblejane.assistant.tools.ToolSpec(
                "zeta_tool", "Z", kotlinx.serialization.json.JsonObject(emptyMap())
            ),
            io.github.leogallego.ansiblejane.assistant.tools.ToolSpec(
                "alpha_tool", "A", kotlinx.serialization.json.JsonObject(emptyMap())
            ),
            io.github.leogallego.ansiblejane.assistant.tools.ToolSpec(
                "middle_tool", "M", kotlinx.serialization.json.JsonObject(emptyMap())
            ),
        )

        engine.processMessage("hi", emptyList(), tools).test {
            while (awaitItem() !is ChatEvent.AssistantMessage) { /* drain */ }
            awaitComplete()
        }

        assertEquals(
            listOf("alpha_tool", "middle_tool", "zeta_tool"),
            provider.lastToolNames
        )
    }

    @Test
    fun `tool-call parse failure SHOULD retry once without tools`() = runTest {
        val provider = ParseFailureThenTextProvider()
        val tools = listOf(
            io.github.leogallego.ansiblejane.assistant.tools.ToolSpec(
                "list_hosts", "List hosts", kotlinx.serialization.json.JsonObject(emptyMap())
            )
        )
        val engine = ChatEngine(provider, ToolExecutor(emptyList()))

        engine.processMessage("how many hosts?", emptyList(), tools).test {
            val delta = awaitItem()
            assertTrue(delta is ChatEvent.TextDelta)
            assertEquals("Plain text fallback", (delta as ChatEvent.TextDelta).text)

            val tokenReport = awaitItem()
            assertTrue(tokenReport is ChatEvent.TokenUsageReport)

            val msg = awaitItem()
            assertTrue(msg is ChatEvent.AssistantMessage)
            assertEquals("Plain text fallback", (msg as ChatEvent.AssistantMessage).fullText)
            awaitComplete()
        }

        assertEquals(2, provider.callCount)
        assertEquals(listOf(1, 0), provider.toolCountsPerCall)
    }

    @Test
    fun `isToolCallParseFailure SHOULD detect parse errors and ignore auth IO`() {
        assertTrue(ChatEngine.isToolCallParseFailure(IllegalArgumentException("Failed to parse tool call JSON")))
        assertTrue(ChatEngine.isToolCallParseFailure(RuntimeException("malformed tool arguments")))
        assertFalse(
            ChatEngine.isToolCallParseFailure(
                io.github.leogallego.ansiblejane.assistant.llm.LlmAuthException("bad key")
            )
        )
        assertFalse(
            ChatEngine.isToolCallParseFailure(
                kotlinx.io.IOException("connection reset")
            )
        )
    }
}

private class CapturingLlmProvider : LlmProvider {
    var lastToolNames: List<String> = emptyList()

    override fun generateStream(
        prompt: Prompt,
        tools: List<ToolDescriptor>,
        maxTokens: Int?
    ): Flow<StreamFrame> = flow {
        lastToolNames = tools.map { it.name }
        emit(StreamFrame.TextDelta("ok"))
        emit(StreamFrame.End(finishReason = "stop"))
    }

    override fun isAvailable(): Boolean = true
    override fun modelInfo(): ModelInfo = ModelInfo("fake-model")
    override fun close() {}
}

private data class FakeToolCall(val id: String, val name: String, val arguments: String)

private data class FakeResponse(
    val text: String? = null,
    val toolCalls: List<FakeToolCall> = emptyList()
)

private class FakeLlmProvider(
    private val responses: List<FakeResponse> = emptyList(),
    private val error: Throwable? = null
) : LlmProvider {
    private var callIndex = 0

    override fun generateStream(
        prompt: Prompt,
        tools: List<ToolDescriptor>,
        maxTokens: Int?
    ): Flow<StreamFrame> = flow {
        if (error != null) {
            throw error
        }
        val resp = responses.getOrElse(callIndex++) { FakeResponse() }
        resp.text?.let { emit(StreamFrame.TextDelta(it)) }
        for (tc in resp.toolCalls) {
            emit(StreamFrame.ToolCallComplete(
                id = tc.id,
                name = tc.name,
                content = tc.arguments
            ))
        }
        emit(StreamFrame.End(finishReason = "stop"))
    }

    override fun isAvailable(): Boolean = true
    override fun modelInfo(): ModelInfo = ModelInfo("fake-model")
    override fun close() {}
}

/** First call with tools throws a parse-like error; second call (no tools) returns text. */
private class ParseFailureThenTextProvider : LlmProvider {
    var callCount = 0
    val toolCountsPerCall = mutableListOf<Int>()

    override fun generateStream(
        prompt: Prompt,
        tools: List<ToolDescriptor>,
        maxTokens: Int?
    ): Flow<StreamFrame> = flow {
        toolCountsPerCall.add(tools.size)
        callCount++
        if (callCount == 1 && tools.isNotEmpty()) {
            throw IllegalArgumentException("Failed to parse tool call JSON")
        }
        emit(StreamFrame.TextDelta("Plain text fallback"))
        emit(StreamFrame.End(finishReason = "stop"))
    }

    override fun isAvailable(): Boolean = true
    override fun modelInfo(): ModelInfo = ModelInfo("fake-model")
    override fun close() {}
}
