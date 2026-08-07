package io.github.leogallego.ansiblejane.network.mcp

import io.github.leogallego.ansiblejane.model.McpServerInfo
import io.github.leogallego.ansiblejane.model.McpToolDefinition
import io.github.leogallego.ansiblejane.network.createPlatformHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.BeforeTest
import kotlin.test.Test
import java.net.Proxy

/**
 * Spike: verify official Kotlin MCP SDK (0.13.0) works with OkHttp engine
 * and maps cleanly to our McpServerManager integration points.
 */
class McpSdkSpikeTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setup() {
        server = MockWebServer()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `SDK Client connects and lists tools via OkHttp engine`() = runBlocking {
        server.dispatcher = mcpDispatcher(
            tools = listOf(
                """{"name":"controller.jobs_read","description":"List all jobs","inputSchema":{"type":"object","properties":{"page_size":{"type":"integer"}}}}""",
                """{"name":"controller.inventories_read","description":"List inventories","inputSchema":{"type":"object","properties":{}}}"""
            )
        )
        server.start()

        val client = createSdkClient()
        client.connect(createTransport())

        val tools = client.listTools().tools

        assertEquals(2, tools.size)
        assertEquals("controller.jobs_read", tools[0].name)
        assertEquals("List all jobs", tools[0].description)
        assertNotNull(tools[0].inputSchema)

        client.close()
    }

    @Test
    fun `SDK Tool maps to McpToolDefinition for cache`() = runBlocking {
        server.dispatcher = mcpDispatcher(
            tools = listOf(
                """{"name":"controller.jobs_read","description":"List all jobs","inputSchema":{"type":"object","properties":{"page_size":{"type":"integer"}}}}"""
            )
        )
        server.start()

        val client = createSdkClient()
        client.connect(createTransport())

        val sdkTool = client.listTools().tools.first()
        val cached = McpToolDefinition(
            name = sdkTool.name,
            description = sdkTool.description ?: "",
            inputSchema = toolSchemaToJsonObject(sdkTool.inputSchema)
        )

        assertEquals("controller.jobs_read", cached.name)
        assertEquals("List all jobs", cached.description)
        assertTrue(cached.inputSchema.containsKey("type"))
        assertTrue(cached.inputSchema.containsKey("properties"))

        client.close()
    }

    @Test
    fun `SDK server version maps to McpServerInfo`() = runBlocking {
        server.dispatcher = mcpDispatcher(tools = emptyList())
        server.start()

        val client = createSdkClient()
        client.connect(createTransport())

        val sv = client.serverVersion
        assertNotNull(sv)
        assertEquals("test-mcp-server", sv!!.name)
        assertEquals("2.1.0", sv.version)

        val ourInfo = McpServerInfo(name = sv.name, version = sv.version)
        assertEquals("test-mcp-server", ourInfo.name)

        client.close()
    }

    @Test
    fun `SDK callTool returns TextContent`() = runBlocking {
        server.dispatcher = mcpDispatcher(
            tools = listOf(
                """{"name":"ping","description":"Ping","inputSchema":{"type":"object","properties":{}}}"""
            ),
            toolCallResponse = """{"content":[{"type":"text","text":"pong from AAP"}],"isError":false}"""
        )
        server.start()

        val client = createSdkClient()
        client.connect(createTransport())

        val result = client.callTool("ping", mapOf("message" to "hello"))
        val texts = result.content.filterIsInstance<TextContent>()

        assertEquals(1, texts.size)
        assertEquals("pong from AAP", texts[0].text)

        client.close()
    }

    @Test
    fun `Platform HTTP client supports self-signed SSL`() {
        val ktorClient = createPlatformHttpClient(trustSelfSigned = true)
        assertNotNull(ktorClient)
        ktorClient.close()
    }

    @Test
    fun `Ktor OkHttp engine supports auth header injection`() = runBlocking {
        val token = "test-bearer-token"
        server.dispatcher = mcpDispatcher(tools = emptyList())
        server.start()

        val ktorClient = HttpClient(OkHttp) {
            engine {
                config {
                    proxy(Proxy.NO_PROXY)
                    addInterceptor { chain ->
                        val req = chain.request().newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build()
                        chain.proceed(req)
                    }
                }
            }
            install(SSE)
        }

        val client = createSdkClient()
        val transport = StreamableHttpClientTransport(
            client = ktorClient,
            url = server.url("/mcp").toString()
        )
        client.connect(transport)

        val initRequest = server.takeRequest()
        assertEquals("Bearer $token", initRequest.getHeader("Authorization"))

        client.close()
        ktorClient.close()
    }

    // -- helpers --

    private fun createSdkClient() = Client(
        clientInfo = Implementation(name = "AnsibleJane", version = "1.0")
    )

    private fun createKtorClient() = HttpClient(OkHttp) {
        engine { config { proxy(Proxy.NO_PROXY) } }
        install(SSE)
    }

    private fun createTransport() = StreamableHttpClientTransport(
        client = createKtorClient(),
        url = server.url("/mcp").toString()
    )

    private fun toolSchemaToJsonObject(schema: ToolSchema) = buildJsonObject {
        put("type", JsonPrimitive(schema.type))
        schema.properties?.let { put("properties", it) }
        schema.required?.let { required ->
            put("required", kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }))
        }
    }

    /**
     * MCP Streamable HTTP mock. The SDK POSTs JSON-RPC and expects either
     * `application/json` or `text/event-stream` responses. GET requests
     * for SSE stream receive 405 (tells SDK to use POST-only mode).
     */
    private fun mcpDispatcher(
        tools: List<String>,
        toolCallResponse: String? = null
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.method == "GET") {
                return MockResponse().setResponseCode(405)
            }

            val body = request.body.readUtf8()
            val id = extractId(body)

            return when {
                body.contains("\"method\":\"initialize\"") -> {
                    val result = """{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"test-mcp-server","version":"2.1.0"}}"""
                    jsonResponse("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
                }
                body.contains("notifications/initialized") ||
                body.contains("notifications/cancelled") -> {
                    MockResponse().setResponseCode(202)
                }
                body.contains("\"method\":\"tools/list\"") -> {
                    val toolsJson = tools.joinToString(",")
                    jsonResponse("""{"jsonrpc":"2.0","id":$id,"result":{"tools":[$toolsJson]}}""")
                }
                body.contains("\"method\":\"tools/call\"") -> {
                    val resultJson = toolCallResponse ?: """{"content":[{"type":"text","text":"ok"}],"isError":false}"""
                    jsonResponse("""{"jsonrpc":"2.0","id":$id,"result":$resultJson}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun extractId(body: String): String {
            val match = Regex(""""id"\s*:\s*"([^"]+)"""").find(body)
            return match?.let { "\"${it.groupValues[1]}\"" } ?: "null"
        }
    }

    private fun jsonResponse(body: String): MockResponse {
        return MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }
}
