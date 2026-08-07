package io.github.leogallego.ansiblejane.network.mcp

import io.github.leogallego.ansiblejane.model.McpConnectionState
import io.github.leogallego.ansiblejane.model.McpServerInfo
import io.github.leogallego.ansiblejane.model.McpToolDefinition
import io.github.leogallego.ansiblejane.assistant.tools.McpTool
import io.github.leogallego.ansiblejane.model.AapInstance
import io.github.leogallego.ansiblejane.model.McpServerConfig
import io.github.leogallego.ansiblejane.model.ServerToolCache
import io.github.leogallego.ansiblejane.model.ToolManifest
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var manager: McpServerManager

    @BeforeTest
    fun setup() {
        server = MockWebServer()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        runBlocking { manager.disconnectAll() }
    }

    private fun createManager(): McpServerManager {
        return McpServerManager(
            ktorClientFactory = { _, _ ->
                HttpClient(OkHttp) {
                    engine { config { proxy(Proxy.NO_PROXY) } }
                    install(SSE)
                }
            }
        )
    }

    private fun createInstance(
        serverUrl: String,
        label: String = "test-server",
        toolset: String? = null,
        enabled: Boolean = true
    ) = AapInstance(
        id = "inst-1",
        baseUrl = "https://aap.example.com",
        token = "token-1",
        mcpServerUrls = listOf(
            McpServerConfig(
                url = serverUrl,
                label = label,
                enabled = enabled,
                toolset = toolset
            )
        )
    )

    // -- connectAll --

    @Test
    fun `connectAll discovers tools and updates connections state`() = runBlocking {
        server.dispatcher = mcpDispatcher(
            tools = listOf(toolJson("ping", "Ping server"))
        )
        server.start()
        manager = createManager()

        val instance = createInstance(server.url("/mcp").toString())
        manager.connectAll(instance)

        val connections = manager.connections.value
        assertEquals(1, connections.size)
        val state = connections["test-server"]
        assertTrue(state is McpConnectionState.Connected)
        assertEquals(1, (state as McpConnectionState.Connected).toolCount)
    }

    @Test
    fun `connectAll registers McpTool objects`() = runBlocking {
        server.dispatcher = mcpDispatcher(
            tools = listOf(
                toolJson("controller.jobs_read", "List jobs"),
                toolJson("controller.hosts_read", "List hosts")
            )
        )
        server.start()
        manager = createManager()

        val instance = createInstance(server.url("/mcp").toString())
        manager.connectAll(instance)

        val tools = manager.mcpTools.value
        assertEquals(2, tools.size)
        assertTrue(tools.all { it is McpTool })
        assertEquals("controller.jobs_read", tools[0].spec.name)
    }

    @Test
    fun `connectAll sets Error state on connection failure`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(500)
        }
        server.start()
        manager = createManager()

        val instance = createInstance(server.url("/mcp").toString())
        manager.connectAll(instance)

        val state = manager.connections.value["test-server"]
        assertTrue(state is McpConnectionState.Error)
    }

    @Test
    fun `connectAll skips disabled servers`() = runBlocking {
        server.start()
        manager = createManager()

        val instance = createInstance(
            server.url("/mcp").toString(),
            enabled = false
        )
        manager.connectAll(instance)

        assertTrue(manager.connections.value.isEmpty())
        assertTrue(manager.mcpTools.value.isEmpty())
    }

    // -- disconnectAll --

    @Test
    fun `disconnectAll clears all state`() = runBlocking {
        server.dispatcher = mcpDispatcher(
            tools = listOf(toolJson("ping", "Ping"))
        )
        server.start()
        manager = createManager()

        val instance = createInstance(server.url("/mcp").toString())
        manager.connectAll(instance)
        assertEquals(1, manager.mcpTools.value.size)

        manager.disconnectAll()

        assertTrue(manager.connections.value.isEmpty())
        assertTrue(manager.mcpTools.value.isEmpty())
    }

    // -- ensureConnected --

    @Test
    fun `ensureConnected connects lazily on first call`() = runBlocking {
        server.dispatcher = mcpDispatcher(
            tools = listOf(toolJson("ping", "Ping"))
        )
        server.start()
        manager = createManager()

        val instance = createInstance(server.url("/mcp").toString())
        manager.connectAll(instance)
        manager.disconnectAll()

        manager.connectAll(
            instance.copy(mcpServerUrls = instance.mcpServerUrls)
        )

        assertEquals(1, manager.mcpTools.value.size)
    }

    // -- reconnectServer --

    @Test
    fun `reconnectServer replaces existing connection`() = runBlocking {
        server.dispatcher = mcpDispatcher(
            tools = listOf(toolJson("ping", "Ping"))
        )
        server.start()
        manager = createManager()

        val instance = createInstance(server.url("/mcp").toString())
        manager.connectAll(instance)
        assertEquals(1, manager.mcpTools.value.size)

        manager.reconnectServer("test-server")

        val tools = manager.mcpTools.value
        assertEquals(1, tools.size)
        val state = manager.connections.value["test-server"]
        assertTrue(state is McpConnectionState.Connected)
    }

    // -- buildManifest --

    @Test
    fun `buildManifest returns manifest from connected servers`() = runBlocking {
        server.dispatcher = mcpDispatcher(
            tools = listOf(
                toolJson("controller.jobs_read", "List jobs"),
                toolJson("controller.hosts_read", "List hosts")
            )
        )
        server.start()
        manager = createManager()

        val instance = createInstance(server.url("/mcp").toString())
        manager.connectAll(instance)

        val manifest = manager.buildManifest(instance)
        assertNotNull(manifest)
        assertEquals("inst-1", manifest!!.instanceId)
        assertEquals(1, manifest.servers.size)
        assertEquals("test-server", manifest.servers[0].label)
        assertEquals(2, manifest.servers[0].tools.size)
        assertEquals("test-mcp-server", manifest.servers[0].serverInfo.name)
    }

    @Test
    fun `buildManifest returns null when no tools`() = runBlocking {
        manager = createManager()
        val instance = createInstance("http://unused")
        assertNull(manager.buildManifest(instance))
    }

    // -- connectAllWithCache --

    @Test
    fun `connectAllWithCache preserves cached tools on version match`() = runBlocking {
        manager = createManager()

        val cachedTools = listOf(
            McpToolDefinition("cached.tool", "A cached tool")
        )
        manager.setCachedTools(
            cachedTools.map { McpTool(
                io.modelcontextprotocol.kotlin.sdk.client.Client(
                    clientInfo = io.modelcontextprotocol.kotlin.sdk.types.Implementation("stub", "0")
                ), it, "test-server", null
            ) }
        )

        val manifest = ToolManifest(
            instanceId = "inst-1",
            servers = listOf(
                ServerToolCache(
                    serverUrl = "http://matching-url/mcp",
                    label = "test-server",
                    serverInfo = McpServerInfo("test-mcp-server", "2.1.0"),
                    tools = cachedTools,
                    readOnly = false
                )
            ),
            cachedAt = System.currentTimeMillis()
        )

        val instance = AapInstance(
            id = "inst-1",
            baseUrl = "https://aap.example.com",
            token = "token-1",
            mcpServerUrls = listOf(
                McpServerConfig(url = "http://matching-url/mcp", label = "test-server", enabled = true)
            )
        )
        manager.connectAllWithCache(instance, manifest)

        val tools = manager.mcpTools.value
        assertEquals(1, tools.size)
        assertEquals("cached.tool", tools[0].spec.name)
    }

    @Test
    fun `connectAllWithCache discovers fresh tools on forceRefresh`() = runBlocking {
        server.dispatcher = mcpDispatcher(
            tools = listOf(toolJson("fresh.tool", "A fresh tool"))
        )
        server.start()
        manager = createManager()

        val manifest = ToolManifest(
            instanceId = "inst-1",
            servers = listOf(
                ServerToolCache(
                    serverUrl = server.url("/mcp").toString(),
                    label = "test-server",
                    serverInfo = McpServerInfo("test-mcp-server", "2.1.0"),
                    tools = listOf(McpToolDefinition("stale.tool", "Stale")),
                    readOnly = false
                )
            ),
            cachedAt = System.currentTimeMillis()
        )

        val instance = createInstance(server.url("/mcp").toString())
        manager.connectAllWithCache(instance, manifest, forceRefresh = true)

        val tools = manager.mcpTools.value
        assertEquals(1, tools.size)
        assertEquals("fresh.tool", tools[0].spec.name)
    }

    @Test
    fun `connectAllWithCache defers connection when all servers cached`() = runBlocking {
        manager = createManager()

        val manifest = ToolManifest(
            instanceId = "inst-1",
            servers = listOf(
                ServerToolCache(
                    serverUrl = "http://not-real/mcp",
                    label = "test-server",
                    serverInfo = McpServerInfo("test-mcp-server", "2.1.0"),
                    tools = listOf(McpToolDefinition("cached.tool", "Cached")),
                    readOnly = false
                )
            ),
            cachedAt = System.currentTimeMillis()
        )

        val instance = AapInstance(
            id = "inst-1",
            baseUrl = "https://aap.example.com",
            token = "token-1",
            mcpServerUrls = listOf(
                McpServerConfig(
                    url = "http://not-real/mcp",
                    label = "test-server",
                    enabled = true
                )
            )
        )

        manager.connectAllWithCache(instance, manifest)

        assertTrue(manager.connections.value.isEmpty())
    }

    @Test
    fun `connectAllWithCache reconnects when server URL changed`() = runBlocking {
        server.dispatcher = mcpDispatcher(
            tools = listOf(toolJson("new.tool", "New tool"))
        )
        server.start()
        manager = createManager()

        val manifest = ToolManifest(
            instanceId = "inst-1",
            servers = listOf(
                ServerToolCache(
                    serverUrl = "http://OLD-url/mcp",
                    label = "test-server",
                    serverInfo = McpServerInfo("test-mcp-server", "2.1.0"),
                    tools = listOf(McpToolDefinition("old.tool", "Old")),
                    readOnly = false
                )
            ),
            cachedAt = System.currentTimeMillis()
        )

        val instance = createInstance(server.url("/mcp").toString())
        manager.connectAllWithCache(instance, manifest)

        val tools = manager.mcpTools.value
        assertEquals(1, tools.size)
        assertEquals("new.tool", tools[0].spec.name)
    }

    @Test
    fun `connectAllWithCache reconnects when toolset changed`() = runBlocking {
        server.dispatcher = mcpDispatcher(
            tools = listOf(toolJson("refreshed.tool", "Refreshed"))
        )
        server.start()
        manager = createManager()

        val serverUrl = server.url("/mcp").toString()

        val manifest = ToolManifest(
            instanceId = "inst-1",
            servers = listOf(
                ServerToolCache(
                    serverUrl = serverUrl,
                    label = "test-server",
                    toolset = "old_toolset",
                    serverInfo = McpServerInfo("test-mcp-server", "2.1.0"),
                    tools = listOf(McpToolDefinition("old.tool", "Old")),
                    readOnly = false
                )
            ),
            cachedAt = System.currentTimeMillis()
        )

        val instance = AapInstance(
            id = "inst-1",
            baseUrl = "https://aap.example.com",
            token = "token-1",
            mcpServerUrls = listOf(
                McpServerConfig(
                    url = serverUrl,
                    label = "test-server",
                    enabled = true,
                    toolset = "new_toolset"
                )
            )
        )
        manager.connectAllWithCache(instance, manifest)

        val tools = manager.mcpTools.value
        assertEquals(1, tools.size)
        assertEquals("refreshed.tool", tools[0].spec.name)
    }

    @Test
    fun `connectAllWithCache skips duplicate labels`() = runBlocking {
        server.dispatcher = mcpDispatcher(
            tools = listOf(toolJson("only.tool", "Only one"))
        )
        server.start()
        manager = createManager()

        val serverUrl = server.url("/mcp").toString()
        val instance = AapInstance(
            id = "inst-1",
            baseUrl = "https://aap.example.com",
            token = "token-1",
            mcpServerUrls = listOf(
                McpServerConfig(url = serverUrl, label = "dup-server", enabled = true),
                McpServerConfig(url = serverUrl, label = "dup-server", enabled = true)
            )
        )
        manager.connectAllWithCache(instance)

        val tools = manager.mcpTools.value
        assertEquals(1, tools.size)
    }

    @Test
    fun `connectAllWithCache with no manifest connects all servers`() = runBlocking {
        server.dispatcher = mcpDispatcher(
            tools = listOf(toolJson("fresh.tool", "Fresh"))
        )
        server.start()
        manager = createManager()

        val instance = createInstance(server.url("/mcp").toString())
        manager.connectAllWithCache(instance, manifest = null)

        val tools = manager.mcpTools.value
        assertEquals(1, tools.size)
        assertEquals("fresh.tool", tools[0].spec.name)
    }

    @Test
    fun `buildManifest includes toolset from config`() = runBlocking {
        server.dispatcher = mcpDispatcher(
            tools = listOf(toolJson("controller.jobs_read", "List jobs"))
        )
        server.start()
        manager = createManager()

        val instance = AapInstance(
            id = "inst-1",
            baseUrl = "https://aap.example.com",
            token = "token-1",
            mcpServerUrls = listOf(
                McpServerConfig(
                    url = server.url("/mcp").toString(),
                    label = "test-server",
                    enabled = true,
                    toolset = "job_management"
                )
            )
        )
        manager.connectAll(instance)

        val manifest = manager.buildManifest(instance)
        assertNotNull(manifest)
        assertEquals("job_management", manifest!!.servers[0].toolset)
    }

    // -- setCachedTools --

    @Test
    fun `setCachedTools replaces all tools`() = runBlocking {
        server.dispatcher = mcpDispatcher(
            tools = listOf(toolJson("live.tool", "Live"))
        )
        server.start()
        manager = createManager()

        val instance = createInstance(server.url("/mcp").toString())
        manager.connectAll(instance)
        assertEquals(1, manager.mcpTools.value.size)

        manager.setCachedTools(emptyList())
        assertTrue(manager.mcpTools.value.isEmpty())
    }

    // -- helpers --

    private fun toolJson(name: String, description: String) =
        """{"name":"$name","description":"$description","inputSchema":{"type":"object","properties":{}}}"""

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
