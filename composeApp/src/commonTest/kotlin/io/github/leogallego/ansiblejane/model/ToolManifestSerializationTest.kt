package io.github.leogallego.ansiblejane.model

import io.github.leogallego.ansiblejane.model.McpServerInfo
import io.github.leogallego.ansiblejane.model.McpToolDefinition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolManifestSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sampleManifest(
        schemaVersion: Int = ToolManifest.CURRENT_SCHEMA_VERSION,
        cachedAt: Long = 1718000000000L
    ) = ToolManifest(
        schemaVersion = schemaVersion,
        instanceId = "inst-1",
        servers = listOf(
            ServerToolCache(
                serverUrl = "http://mcp.example.com/mcp",
                label = "test-server",
                toolset = "job_management",
                serverInfo = McpServerInfo("aap-mcp", "2.1.0"),
                tools = listOf(
                    McpToolDefinition(
                        name = "controller.jobs_read",
                        description = "List jobs",
                        inputSchema = buildJsonObject {
                            put("type", JsonPrimitive("object"))
                        }
                    ),
                    McpToolDefinition(
                        name = "controller.jobs_create",
                        description = "Create a job"
                    )
                ),
                readOnly = false
            )
        ),
        cachedAt = cachedAt
    )

    @Test
    fun `SHOULD preserve all fields WHEN roundtrip serialized`() {
        val manifest = sampleManifest()
        val encoded = json.encodeToString(manifest)
        val decoded = json.decodeFromString<ToolManifest>(encoded)

        assertEquals(manifest.schemaVersion, decoded.schemaVersion)
        assertEquals(manifest.instanceId, decoded.instanceId)
        assertEquals(manifest.cachedAt, decoded.cachedAt)
        assertEquals(manifest.servers.size, decoded.servers.size)

        val server = decoded.servers[0]
        assertEquals("http://mcp.example.com/mcp", server.serverUrl)
        assertEquals("test-server", server.label)
        assertEquals("job_management", server.toolset)
        assertEquals("aap-mcp", server.serverInfo.name)
        assertEquals("2.1.0", server.serverInfo.version)
        assertEquals(2, server.tools.size)
        assertEquals("controller.jobs_read", server.tools[0].name)
        assertEquals(false, server.readOnly)
    }

    @Test
    fun `SHOULD preserve inputSchema WHEN tool roundtrip serialized`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("name", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                })
            })
        }
        val tool = McpToolDefinition("my_tool", "desc", schema)
        val manifest = ToolManifest(
            instanceId = "inst-1",
            servers = listOf(
                ServerToolCache(
                    serverUrl = "http://x/mcp",
                    label = "s1",
                    serverInfo = McpServerInfo("srv", "1.0"),
                    tools = listOf(tool),
                    readOnly = true
                )
            ),
            cachedAt = 1000L
        )

        val decoded = json.decodeFromString<ToolManifest>(json.encodeToString(manifest))
        assertEquals(schema, decoded.servers[0].tools[0].inputSchema)
    }

    @Test
    fun `SHOULD default to CURRENT_SCHEMA_VERSION WHEN no version specified`() {
        val manifest = ToolManifest(
            instanceId = "inst-1",
            servers = emptyList(),
            cachedAt = 0L
        )
        assertEquals(ToolManifest.CURRENT_SCHEMA_VERSION, manifest.schemaVersion)
    }

    @Test
    fun `SHOULD ignore unknown fields WHEN deserializing`() {
        val jsonString = """
        {
            "schemaVersion": 1,
            "instanceId": "inst-1",
            "servers": [],
            "cachedAt": 12345,
            "futureField": "ignored"
        }
        """.trimIndent()
        val manifest = json.decodeFromString<ToolManifest>(jsonString)
        assertEquals("inst-1", manifest.instanceId)
        assertEquals(12345L, manifest.cachedAt)
    }

    @Test
    fun `SHOULD use empty defaults WHEN McpToolDefinition created with name only`() {
        val tool = McpToolDefinition("ping")
        assertEquals("", tool.description)
        assertEquals(JsonObject(emptyMap()), tool.inputSchema)
    }

    @Test
    fun `SHOULD preserve null toolset WHEN ServerToolCache roundtripped`() {
        val cache = ServerToolCache(
            serverUrl = "http://x",
            label = "s1",
            serverInfo = McpServerInfo("srv", "1.0"),
            tools = emptyList(),
            readOnly = false
        )
        assertNull(cache.toolset)

        val encoded = json.encodeToString(cache)
        val decoded = json.decodeFromString<ServerToolCache>(encoded)
        assertNull(decoded.toolset)
    }

    @Test
    fun `SHOULD throw WHEN JSON is corrupted`() {
        val corrupted = """{"schemaVersion":1,"instanceId":"x","server"""
        val result = try {
            json.decodeFromString<ToolManifest>(corrupted)
            "parsed"
        } catch (_: Exception) {
            "failed"
        }
        assertEquals("failed", result)
    }

    @Test
    fun `SHOULD roundtrip WHEN servers list is empty`() {
        val manifest = ToolManifest(
            instanceId = "inst-empty",
            servers = emptyList(),
            cachedAt = 0L
        )
        val decoded = json.decodeFromString<ToolManifest>(json.encodeToString(manifest))
        assertEquals(0, decoded.servers.size)
    }

    @Test
    fun `SHOULD preserve all servers WHEN multiple servers roundtripped`() {
        val manifest = ToolManifest(
            instanceId = "inst-multi",
            servers = listOf(
                ServerToolCache(
                    serverUrl = "http://a/mcp", label = "a",
                    serverInfo = McpServerInfo("a", "1.0"),
                    tools = listOf(McpToolDefinition("tool_a", "A")),
                    readOnly = false
                ),
                ServerToolCache(
                    serverUrl = "http://b/mcp", label = "b",
                    toolset = "eda",
                    serverInfo = McpServerInfo("b", "2.0"),
                    tools = listOf(McpToolDefinition("tool_b", "B")),
                    readOnly = true
                )
            ),
            cachedAt = 999L
        )
        val decoded = json.decodeFromString<ToolManifest>(json.encodeToString(manifest))
        assertEquals(2, decoded.servers.size)
        assertEquals("a", decoded.servers[0].label)
        assertEquals("b", decoded.servers[1].label)
        assertEquals(true, decoded.servers[1].readOnly)
        assertEquals("eda", decoded.servers[1].toolset)
    }
}
