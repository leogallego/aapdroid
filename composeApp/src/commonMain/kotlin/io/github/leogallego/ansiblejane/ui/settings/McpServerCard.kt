package io.github.leogallego.ansiblejane.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.leogallego.ansiblejane.model.McpServerConfig
import io.github.leogallego.ansiblejane.model.McpConnectionState
import io.github.leogallego.ansiblejane.presentation.settings.McpToolUiState
import org.jetbrains.compose.resources.stringResource
import aapremotecontrol.composeapp.generated.resources.*
import io.github.leogallego.ansiblejane.ui.theme.AnsibleJaneTheme

@Composable
fun McpServerCard(
    server: McpServerConfig,
    connectionState: McpConnectionState?,
    tools: List<McpToolUiState>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleReadOnly: (Boolean) -> Unit,
    onToggleUseInstanceAuth: (Boolean) -> Unit,
    onToggleTool: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColors = AnsibleJaneTheme.statusColors
    val dotColor = when (connectionState) {
        is McpConnectionState.Connected -> statusColors.successful
        is McpConnectionState.Connecting -> statusColors.running
        is McpConnectionState.Error -> statusColors.error
        else -> statusColors.disconnected
    }

    val statusText = when (connectionState) {
        is McpConnectionState.Connected -> stringResource(Res.string.mcp_status_connected)
        is McpConnectionState.Connecting -> stringResource(Res.string.mcp_status_connecting)
        is McpConnectionState.Error -> stringResource(Res.string.mcp_status_error)
        else -> stringResource(Res.string.mcp_status_disconnected)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("card_mcp_server_${server.label}")
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                        .semantics { contentDescription = statusText }
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onToggleExpand() }
                ) {
                    Text(
                        text = server.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = server.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag("switch_mcp_server_${server.label}")
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) stringResource(Res.string.cd_collapse) else stringResource(Res.string.cd_expand),
                    modifier = Modifier.clickable { onToggleExpand() },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = server.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (server.toolset != null) {
                        Text(
                            text = stringResource(Res.string.mcp_toolset_label, server.toolset!!),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(Res.string.mcp_read_only),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = server.readOnly,
                            onCheckedChange = onToggleReadOnly,
                            modifier = Modifier.testTag("switch_mcp_readonly_${server.label}")
                        )
                    }

                    if (!server.isAutoDetected) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(Res.string.mcp_use_instance_auth),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = server.useInstanceAuth,
                                onCheckedChange = onToggleUseInstanceAuth,
                                modifier = Modifier.testTag("switch_mcp_instance_auth_${server.label}")
                            )
                        }
                    }

                    if (server.headers.isNotEmpty()) {
                        Text(
                            text = stringResource(Res.string.mcp_custom_headers, server.headers.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier.testTag("button_mcp_refresh_${server.label}")
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(Res.string.cd_refresh)
                            )
                        }
                        if (!server.isAutoDetected) {
                            IconButton(
                                onClick = onRemove,
                                modifier = Modifier.testTag("button_mcp_remove_${server.label}")
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(Res.string.cd_remove),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    if (connectionState is McpConnectionState.Error) {
                        Text(
                            text = stringResource(Res.string.mcp_retry_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    if (connectionState is McpConnectionState.Connected) {
                        HorizontalDivider()
                        if (tools.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.mcp_no_tools),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            tools.forEach { tool ->
                                ToolItemRow(
                                    name = tool.name,
                                    description = tool.description,
                                    isEnabled = tool.isEnabled,
                                    isAutoDisabled = tool.isAutoDisabled,
                                    testTagPrefix = "switch_mcp_tool",
                                    onToggle = { onToggleTool(tool.name, it) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
