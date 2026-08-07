package io.github.leogallego.ansiblejane.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import aapremotecontrol.composeapp.generated.resources.*
import io.github.leogallego.ansiblejane.model.popularMcpServers
import io.ktor.http.parseUrl

private data class HeaderEntry(val key: String = "", val value: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMcpServerSheet(
    onDismiss: () -> Unit,
    onAdd: (url: String, label: String, toolset: String?, headers: Map<String, String>, useInstanceAuth: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var toolset by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var useInstanceAuth by remember { mutableStateOf(true) }
    val headers = remember { mutableStateListOf<HeaderEntry>() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(Res.string.add_mcp_title),
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.add_mcp_label_name)) },
                placeholder = { Text(stringResource(Res.string.add_mcp_placeholder_name)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("field_add_mcp_name"),
                singleLine = true
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it; urlError = null },
                label = { Text(stringResource(Res.string.add_mcp_label_url)) },
                placeholder = { Text(stringResource(Res.string.add_mcp_placeholder_url)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("field_add_mcp_url"),
                singleLine = true,
                isError = urlError != null,
                supportingText = urlError?.let { error -> { Text(error) } }
            )

            OutlinedTextField(
                value = toolset,
                onValueChange = { toolset = it },
                label = { Text(stringResource(Res.string.add_mcp_label_toolset)) },
                placeholder = { Text(stringResource(Res.string.add_mcp_placeholder_toolset)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("field_add_mcp_toolset"),
                singleLine = true
            )

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
                    checked = useInstanceAuth,
                    onCheckedChange = { useInstanceAuth = it },
                    modifier = Modifier.testTag("switch_add_mcp_instance_auth")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Res.string.add_mcp_custom_headers),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(
                    onClick = { headers.add(HeaderEntry(key = "Authorization")) },
                    modifier = Modifier.testTag("button_add_header")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(Res.string.add_mcp_add_header),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            headers.forEachIndexed { index, entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = entry.key,
                        onValueChange = { headers[index] = entry.copy(key = it) },
                        label = { Text(stringResource(Res.string.add_mcp_label_header_name)) },
                        placeholder = { Text(stringResource(Res.string.add_mcp_placeholder_header_name)) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("field_header_key_$index"),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = entry.value,
                        onValueChange = { headers[index] = entry.copy(value = it) },
                        label = { Text(stringResource(Res.string.add_mcp_label_header_value)) },
                        placeholder = { Text(stringResource(Res.string.add_mcp_placeholder_header_value)) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("field_header_value_$index"),
                        singleLine = true
                    )
                    IconButton(
                        onClick = { headers.removeAt(index) },
                        modifier = Modifier.testTag("button_remove_header_$index")
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(Res.string.cd_remove_header),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(Res.string.btn_cancel)) }
                Button(
                    onClick = {
                        val error = validateMcpUrl(url)
                        if (error != null) {
                            urlError = error
                        } else {
                            val headersMap = headers
                                .filter { it.key.isNotBlank() && it.value.isNotBlank() }
                                .associate { it.key.trim() to it.value.trim() }
                            onAdd(url, name, toolset.ifBlank { null }, headersMap, useInstanceAuth)
                            onDismiss()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("button_add_mcp_server"),
                    enabled = name.isNotBlank() && url.isNotBlank()
                ) { Text(stringResource(Res.string.tools_add_mcp_server)) }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = stringResource(Res.string.add_mcp_popular_servers),
                style = MaterialTheme.typography.titleSmall
            )

            popularMcpServers.forEach { server ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("card_popular_${server.name}")
                        .clickable {
                            onAdd(server.url, server.name, null, emptyMap(), false)
                            onDismiss()
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = server.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun validateMcpUrl(url: String): String? {
    val sanitized = url.trim().trimEnd('/')
    if (sanitized.isBlank()) return "URL is required"
    val parsed = parseUrl(sanitized) ?: return "Invalid URL format"
    if (parsed.protocol.name !in listOf("https", "wss")) return "Only HTTPS and WSS URLs are supported"
    return null
}
