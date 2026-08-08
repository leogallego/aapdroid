package io.github.leogallego.ansiblejane.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class McpToolDefinition(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class McpServerInfo(
    val name: String,
    val version: String
)

sealed interface McpConnectionState {
    data object Disconnected : McpConnectionState
    data object Connecting : McpConnectionState
    data class Connected(
        val serverInfo: McpServerInfo,
        val toolCount: Int
    ) : McpConnectionState
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : McpConnectionState
}
