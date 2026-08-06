package io.github.leogallego.ansiblejane.ui.jobs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import io.github.leogallego.ansiblejane.model.Job
import io.github.leogallego.ansiblejane.presentation.jobs.JobStatusUiState
import io.github.leogallego.ansiblejane.presentation.jobs.JobStatusViewModel
import io.github.leogallego.ansiblejane.ui.components.DateFormatter
import io.github.leogallego.ansiblejane.ui.components.DetailRowHorizontal
import io.github.leogallego.ansiblejane.ui.components.DetailScaffold
import io.github.leogallego.ansiblejane.ui.components.ErrorMessage
import io.github.leogallego.ansiblejane.ui.components.AnsiText
import io.github.leogallego.ansiblejane.ui.components.JobStatusBadge
import org.jetbrains.compose.resources.stringResource
import aapremotecontrol.composeapp.generated.resources.*
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun JobStatusScreen(
    onNavigateBack: () -> Unit,
    viewModel: JobStatusViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }

    DetailScaffold(title = stringResource(Res.string.job_status_title), onNavigateBack = onNavigateBack) {
            when (val state = uiState) {
                is JobStatusUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is JobStatusUiState.Error -> {
                    ErrorMessage(
                        error = state.error,
                        onRetry = { viewModel.retry() },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is JobStatusUiState.Active -> {
                    JobDetailContent(job = state.job, isActive = true, stdout = state.stdout)
                }
                is JobStatusUiState.Completed -> {
                    JobDetailContent(job = state.job, isActive = false, stdout = state.stdout)
                }
            }
        }
    }

@Composable
private fun JobDetailContent(job: Job, isActive: Boolean, stdout: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = job.name,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.testTag("text_job_name")
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.job_detail_template, job.jobTemplateName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    JobStatusBadge(status = job.status, modifier = Modifier.testTag("badge_job_status"))
                    if (isActive) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(start = 8.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(Res.string.label_details), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                job.started?.let { started ->
                    DetailRowHorizontal(stringResource(Res.string.job_detail_started), DateFormatter.formatDateTime(started))
                }
                job.finished?.let { finished ->
                    DetailRowHorizontal(stringResource(Res.string.job_detail_finished), DateFormatter.formatDateTime(finished))
                }
                job.elapsed?.let { elapsed ->
                    DetailRowHorizontal(stringResource(Res.string.job_detail_elapsed), String.format("%.1f seconds", elapsed))
                }
                DetailRowHorizontal(stringResource(Res.string.job_detail_job_id), "#${job.id}")
            }
        }

        if (!stdout.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(Res.string.label_output), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AnsiText(
                            text = stdout,
                            modifier = Modifier
                                .padding(12.dp)
                                .horizontalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
}

