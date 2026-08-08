package io.github.leogallego.ansiblejane.data

import io.github.leogallego.ansiblejane.assistant.tools.Tool
import io.github.leogallego.ansiblejane.model.AapInstance
import io.github.leogallego.ansiblejane.model.McpConnectionState
import io.github.leogallego.ansiblejane.model.ToolManifest
import kotlinx.coroutines.flow.StateFlow

/**
 * Presentation-safe facade over MCP server lifecycle.
 *
 * Wraps [io.github.leogallego.ansiblejane.network.mcp.McpServerManager] so ViewModels
 * do not import the network layer. MCP tool execution uses
 * [io.github.leogallego.ansiblejane.assistant.tools.McpToolInvoker].
 */
interface IMcpConnectionRepository {
    val connections: StateFlow<Map<String, McpConnectionState>>
    val mcpTools: StateFlow<List<Tool>>

    suspend fun connectAll(instance: AapInstance)
    suspend fun disconnectAll()
    fun setCachedTools(tools: List<Tool>)
    suspend fun connectAllWithCache(
        instance: AapInstance,
        manifest: ToolManifest? = null,
        forceRefresh: Boolean = false
    )
    suspend fun reconnectServer(label: String)
    fun refreshConnections()
    fun buildManifest(instance: AapInstance): ToolManifest?
}
