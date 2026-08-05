package io.github.leogallego.ansiblejane.assistant.llm

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.assistant.tools.ToolSpec
import io.github.leogallego.ansiblejane.assistant.tools.toToolDescriptor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KoogLlmProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: KoogLlmProvider

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()

        val config = LlmProviderConfig.OpenAiCompatible(
            url = server.url("/v1").toString(),
            model = "test-model",
            apiKey = "test-key"
        )
        provider = KoogLlmProvider(config)
    }

    @AfterTest
    fun tearDown() {
        provider.close()
        server.shutdown()
    }

    private fun userPrompt(content: String): Prompt = Prompt(
        messages = listOf(Message.User(content = content, metaInfo = RequestMetaInfo.Empty)),
        id = "test"
    )

    private fun sseTextResponse(content: String): MockResponse {
        val chunk = """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"test-model",""" +
            """"choices":[{"index":0,"delta":{"role":"assistant","content":"$content"},"finish_reason":null}]}"""
        val done = """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"test-model",""" +
            """"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
        return MockResponse()
            .setBody("data: $chunk\n\ndata: $done\n\ndata: [DONE]\n\n")
            .setHeader("Content-Type", "text/event-stream")
    }

    private fun sseToolCallResponse(): MockResponse {
        val chunk1 = """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"test-model",""" +
            """"choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"list_jobs","arguments":""}}]},"finish_reason":null}]}"""
        val chunk2 = """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"test-model",""" +
            """"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"status\":\"failed\"}"}}]},"finish_reason":null}]}"""
        val done = """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"test-model",""" +
            """"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
        return MockResponse()
            .setBody("data: $chunk1\n\ndata: $chunk2\n\ndata: $done\n\ndata: [DONE]\n\n")
            .setHeader("Content-Type", "text/event-stream")
    }

    @Test
    fun `generateStream sends correct request format`() = runTest {
        server.enqueue(sseTextResponse("Hello!"))

        provider.generateStream(userPrompt("Hi"), emptyList()).toList()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/chat/completions"))
        assertEquals("Bearer test-key", request.getHeader("Authorization"))

        val body = request.body.readUtf8()
        assertTrue(body.contains("\"model\":\"test-model\""))
    }

    @Test
    fun `generateStream parses text response`() = runTest {
        server.enqueue(sseTextResponse("The answer is 42"))

        val frames = provider.generateStream(userPrompt("What is the answer?"), emptyList()).toList()

        val textFrames = frames.filterIsInstance<StreamFrame.TextDelta>()
        assertTrue(textFrames.isNotEmpty())
        val fullText = textFrames.joinToString("") { it.text }
        assertTrue(fullText.contains("The answer is 42"))
    }

    @Test
    fun `generateStream parses tool calls`() = runTest {
        server.enqueue(sseToolCallResponse())

        val frames = provider.generateStream(userPrompt("List failed jobs"), emptyList()).toList()

        val toolCalls = frames.filterIsInstance<StreamFrame.ToolCallComplete>()
        assertEquals(1, toolCalls.size)
        assertEquals("call_1", toolCalls[0].id)
        assertEquals("list_jobs", toolCalls[0].name)
    }

    @Test
    fun `generateStream serializes tool call arguments without double encoding`() = runTest {
        // Regression for Koog #2095 / Jane FixedOpenAILLMClient removal:
        // args must be emitted as a JSON object string, not a JSON-encoded string.
        server.enqueue(sseTextResponse("done"))

        val prompt = Prompt(
            messages = listOf(
                Message.User(content = "List failed jobs", metaInfo = RequestMetaInfo.Empty),
                Message.Assistant(
                    parts = listOf(
                        MessagePart.Tool.Call(
                            id = "call_1",
                            tool = "list_jobs",
                            args = """{"status":"failed"}"""
                        )
                    ),
                    metaInfo = ResponseMetaInfo.Empty
                ),
                Message.User(
                    part = MessagePart.Tool.Result(
                        id = "call_1",
                        tool = "list_jobs",
                        output = "[]"
                    ),
                    metaInfo = RequestMetaInfo.Empty
                )
            ),
            id = "tool-roundtrip"
        )

        provider.generateStream(prompt, emptyList()).toList()

        val body = server.takeRequest().body.readUtf8()
        assertTrue(
            body.contains(""""arguments":"{\"status\":\"failed\"}"""") ||
                body.contains(""""arguments":{"status":"failed"}"""),
            "Expected single-encoded tool args in request body, got: $body"
        )
        assertFalse(
            body.contains(""""arguments":"\"{\\\"status\\\":\\\"failed\\\"}\""""),
            "Tool args were double-encoded: $body"
        )
    }

    @Test
    fun `generateStream throws LlmAuthException on 401`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setChunkedBody("""{"error":{"message":"Unauthorized"}}""", 1024)
        )

        try {
            provider.generateStream(userPrompt("test"), emptyList()).toList()
            throw AssertionError("Expected exception was not thrown")
        } catch (e: LlmAuthException) {
            // Expected
        }
    }

    @Test
    fun `generateStream throws LlmRateLimitException on 429`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setChunkedBody("""{"error":{"message":"Rate limit exceeded"}}""", 1024)
        )

        try {
            provider.generateStream(userPrompt("test"), emptyList()).toList()
            throw AssertionError("Expected exception was not thrown")
        } catch (e: LlmRateLimitException) {
            // Expected
        }
    }

    @Test
    fun `generateStream throws LlmServerException on 500`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setChunkedBody("""{"error":{"message":"Internal Server Error"}}""", 1024)
        )

        try {
            provider.generateStream(userPrompt("test"), emptyList()).toList()
            throw AssertionError("Expected exception was not thrown")
        } catch (e: LlmServerException) {
            // Expected
        }
    }

    @Test
    fun `generateStream includes tools in request when provided`() = runTest {
        server.enqueue(sseTextResponse("ok"))

        val tools = listOf(
            ToolSpec(
                name = "list_jobs",
                description = "List AAP jobs",
                parametersSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("status") {
                            put("type", "string")
                        }
                    }
                }
            )
        )

        provider.generateStream(userPrompt("test"), tools.map { it.toToolDescriptor() }).toList()

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("list_jobs"))
    }

    @Test
    fun `isAvailable returns true when config is valid`() {
        assertTrue(provider.isAvailable())
    }

    @Test
    fun `modelInfo returns correct model name`() {
        val info = provider.modelInfo()
        assertEquals("test-model", info.name)
        assertEquals(false, info.isLocal)
    }
}
