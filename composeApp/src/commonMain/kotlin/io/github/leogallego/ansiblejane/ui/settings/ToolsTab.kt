package io.github.leogallego.ansiblejane.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import aapremotecontrol.composeapp.generated.resources.*
import io.github.leogallego.ansiblejane.assistant.tools.ToolSource
import io.github.leogallego.ansiblejane.model.McpServerConfig
import io.github.leogallego.ansiblejane.model.McpConnectionState
import io.github.leogallego.ansiblejane.presentation.settings.McpToolUiState

@Composable
fun ToolsTab(
    mcpEnabled: Boolean,
    mcpServers: List<McpServerConfig>,
    connections: Map<String, McpConnectionState>,
    mcpServerTools: Map<String, List<McpToolUiState>>,
    expandedMcpServers: Set<String>,
    onToggleMcp: (Boolean) -> Unit,
    onAddMcpServer: (url: String, label: String, toolset: String?, headers: Map<String, String>, useInstanceAuth: Boolean) -> Unit,
    onRemoveMcpServer: (url: String) -> Unit,
    onToggleReadOnly: (url: String, readOnly: Boolean) -> Unit,
    onToggleUseInstanceAuth: (url: String, useInstanceAuth: Boolean) -> Unit,
    onToggleServerEnabled: (url: String, enabled: Boolean) -> Unit,
    onToggleToolEnabled: (name: String, source: ToolSource, serverLabel: String?, enabled: Boolean) -> Unit,
    onToggleExpandMcpServer: (label: String) -> Unit,
    onRefreshMcpServer: (label: String) -> Unit,
    isRefreshingTools: Boolean = false,
    onRefreshAllTools: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showAddServerSheet by remember { mutableStateOf(false) }
    var serversExpanded by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // MCP Section
        Text(
            text = stringResource(Res.string.tools_section_mcp_servers),
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(stringResource(Res.string.tools_enable_mcp), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(Res.string.tools_enable_mcp_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = mcpEnabled,
                onCheckedChange = onToggleMcp,
                modifier = Modifier.testTag("switch_mcp_enabled")
            )
        }

        if (mcpEnabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { serversExpanded = !serversExpanded }
                    .padding(vertical = 4.dp)
                    .testTag("section_mcp_servers"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Res.string.tools_servers_header),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                val connectedCount = connections.count { it.value is McpConnectionState.Connected }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "$connectedCount/${mcpServers.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (serversExpanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (serversExpanded) stringResource(Res.string.cd_collapse) else stringResource(Res.string.cd_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = serversExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    mcpServers.forEach { server ->
                        McpServerCard(
                            server = server,
                            connectionState = connections[server.label],
                            tools = mcpServerTools[server.label] ?: emptyList(),
                            expanded = server.label in expandedMcpServers,
                            onToggleExpand = { onToggleExpandMcpServer(server.label) },
                            onToggleEnabled = { onToggleServerEnabled(server.url, it) },
                            onToggleReadOnly = { onToggleReadOnly(server.url, it) },
                            onToggleUseInstanceAuth = { onToggleUseInstanceAuth(server.url, it) },
                            onToggleTool = { name, enabled ->
                                onToggleToolEnabled(name, ToolSource.MCP, server.label, enabled)
                            },
                            onRefresh = { onRefreshMcpServer(server.label) },
                            onRemove = { onRemoveMcpServer(server.url) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showAddServerSheet = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                stringResource(Res.string.tools_add_mcp_server),
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                        OutlinedButton(
                            onClick = onRefreshAllTools,
                            enabled = !isRefreshingTools,
                            modifier = Modifier.testTag("button_refresh_tools")
                        ) {
                            if (isRefreshingTools) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(Res.string.cd_refresh),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

    }

    if (showAddServerSheet) {
        AddMcpServerSheet(
            onDismiss = { showAddServerSheet = false },
            onAdd = { url, label, toolset, headers, useInstanceAuth ->
                onAddMcpServer(url, label, toolset, headers, useInstanceAuth)
            }
        )
    }
}
