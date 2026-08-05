package io.github.leogallego.ansiblejane.ui.hosts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import io.github.leogallego.ansiblejane.model.Host
import io.github.leogallego.ansiblejane.model.JobHostSummary
import io.github.leogallego.ansiblejane.presentation.hosts.HostDetailUiState
import io.github.leogallego.ansiblejane.presentation.hosts.HostDetailViewModel
import io.github.leogallego.ansiblejane.ui.components.DetailRow
import io.github.leogallego.ansiblejane.ui.components.DetailSheetHeader
import org.jetbrains.compose.resources.stringResource
import aapremotecontrol.composeapp.generated.resources.*
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostDetailSheet(
    host: Host,
    onDismiss: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = isExpanded)

    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        if (isExpanded) {
            HostDetailFullScreen(
                host = host,
                onClose = onDismiss
            )
        } else {
            HostDetailCompact(
                host = host,
                onExpand = {
                    isExpanded = true
                    scope.launch { sheetState.expand() }
                }
            )
        }
    }
}

@Composable
private fun HostDetailCompact(
    host: Host,
    onExpand: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        DetailSheetHeader(title = host.name, onExpand = onExpand)
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        if (host.hasActiveFailures) {
            DetailRow(label = stringResource(Res.string.label_status), value = stringResource(Res.string.host_status_active_failures))
        }

        host.summaryFields.inventory?.let {
            DetailRow(label = stringResource(Res.string.host_label_inventory), value = it.name)
        }

        DetailRow(label = stringResource(Res.string.label_enabled), value = if (host.enabled) stringResource(Res.string.label_yes) else stringResource(Res.string.label_no))
        DetailRow(label = stringResource(Res.string.label_created), value = host.created)
        DetailRow(label = stringResource(Res.string.label_modified), value = host.modified)

        if (host.variables.isNotBlank() && host.variables != "{}") {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.label_variables),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = host.variables,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HostDetailFullScreen(
    host: Host,
    onClose: () -> Unit
) {
    val viewModel: HostDetailViewModel = koinViewModel(
        key = "host-detail-${host.id}",
        parameters = { parametersOf(host.id) },
    )
    val uiState by viewModel.uiState.collectAsState()
    val content = uiState as? HostDetailUiState.Content ?: HostDetailUiState.Content()
    val facts = content.facts
    val factsLoading = content.factsLoading
    val jobSummaries = content.jobSummaries
    val jobsLoading = content.jobsLoading

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = host.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.cd_close))
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Details section
            item {
                Text(
                    text = stringResource(Res.string.host_section_details),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            item {
                host.summaryFields.inventory?.let {
                    DetailRow(label = stringResource(Res.string.host_label_inventory), value = it.name)
                }
                DetailRow(label = stringResource(Res.string.label_enabled), value = if (host.enabled) stringResource(Res.string.label_yes) else stringResource(Res.string.label_no))
                DetailRow(label = stringResource(Res.string.label_created), value = host.created)
                DetailRow(label = stringResource(Res.string.label_modified), value = host.modified)
                if (host.hasActiveFailures) {
                    DetailRow(label = stringResource(Res.string.label_status), value = stringResource(Res.string.host_status_active_failures))
                }
            }

            // Groups section
            val groups = host.summaryFields.groups?.results.orEmpty()
            if (groups.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.host_section_groups, groups.size),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        groups.forEach { group ->
                            AssistChip(
                                onClick = {},
                                label = { Text(group.name) }
                            )
                        }
                    }
                }
            }

            // Facts section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.host_section_facts),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            if (factsLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            } else {
                val factEntries = facts.entries.toList()
                if (factEntries.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(Res.string.host_facts_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(factEntries, key = { it.key }) { (key, value) ->
                        DetailRow(label = key, value = value.toString())
                    }
                }
            }

            // Jobs section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.host_section_jobs),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            if (jobsLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            } else if (jobSummaries.isEmpty()) {
                item {
                    Text(
                        text = stringResource(Res.string.host_jobs_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(jobSummaries, key = { it.id }) { summary ->
                    JobHostSummaryItem(summary = summary)
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun JobHostSummaryItem(
    summary: JobHostSummary,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (summary.failed) Icons.Default.Error else Icons.Default.CheckCircle,
                contentDescription = if (summary.failed) stringResource(Res.string.cd_host_job_failed) else stringResource(Res.string.cd_host_job_successful),
                tint = if (summary.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.summaryFields.job?.name ?: stringResource(Res.string.host_job_fallback_name, summary.job),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(Res.string.host_job_summary_stats, summary.ok, summary.changed, summary.failures, summary.skipped),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = summary.created,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

