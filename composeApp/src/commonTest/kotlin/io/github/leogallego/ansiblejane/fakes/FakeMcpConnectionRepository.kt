package io.github.leogallego.ansiblejane.fakes

import io.github.leogallego.ansiblejane.assistant.tools.Tool
import io.github.leogallego.ansiblejane.data.IMcpConnectionRepository
import io.github.leogallego.ansiblejane.model.AapInstance
import io.github.leogallego.ansiblejane.model.McpConnectionState
import io.github.leogallego.ansiblejane.model.ToolManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeMcpConnectionRepository : IMcpConnectionRepository {
    private val _connections = MutableStateFlow<Map<String, McpConnectionState>>(emptyMap())
    override val connections: StateFlow<Map<String, McpConnectionState>> = _connections.asStateFlow()

    private val _mcpTools = MutableStateFlow<List<Tool>>(emptyList())
    override val mcpTools: StateFlow<List<Tool>> = _mcpTools.asStateFlow()

    var disconnectAllCalls = 0
    var connectAllCalls = 0
    var connectAllWithCacheCalls = 0
    var refreshConnectionsCalls = 0
    var reconnectServerCalls = mutableListOf<String>()
    var lastManifest: ToolManifest? = null

    override suspend fun connectAll(instance: AapInstance) {
        connectAllCalls++
    }

    override suspend fun disconnectAll() {
        disconnectAllCalls++
        _connections.value = emptyMap()
        _mcpTools.value = emptyList()
    }

    override fun setCachedTools(tools: List<Tool>) {
        _mcpTools.value = tools
    }

    override suspend fun connectAllWithCache(
        instance: AapInstance,
        manifest: ToolManifest?,
        forceRefresh: Boolean
    ) {
        connectAllWithCacheCalls++
    }

    override suspend fun reconnectServer(label: String) {
        reconnectServerCalls.add(label)
    }

    override fun refreshConnections() {
        refreshConnectionsCalls++
    }

    override fun buildManifest(instance: AapInstance): ToolManifest? = lastManifest

    fun setConnections(value: Map<String, McpConnectionState>) {
        _connections.value = value
    }
}
