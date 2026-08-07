package io.github.leogallego.ansiblejane.model

import kotlinx.serialization.Serializable

@Serializable
data class ToolManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val instanceId: String,
    val servers: List<ServerToolCache>,
    val cachedAt: Long
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class ServerToolCache(
    val serverUrl: String,
    val label: String,
    val toolset: String? = null,
    val serverInfo: McpServerInfo,
    val tools: List<McpToolDefinition>,
    val readOnly: Boolean
)
