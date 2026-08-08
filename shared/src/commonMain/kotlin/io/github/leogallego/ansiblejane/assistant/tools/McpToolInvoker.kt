package io.github.leogallego.ansiblejane.assistant.tools

import kotlinx.serialization.json.JsonObject

/**
 * Executes an MCP tool call against a named server connection.
 *
 * Presentation-safe: no MCP SDK types. Implemented by
 * [io.github.leogallego.ansiblejane.network.mcp.McpServerManager].
 */
fun interface McpToolInvoker {
    suspend fun invokeMcpTool(
        serverLabel: String,
        toolName: String,
        args: JsonObject
    ): ToolResult
}
