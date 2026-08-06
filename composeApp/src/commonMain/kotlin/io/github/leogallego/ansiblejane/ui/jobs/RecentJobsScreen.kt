package io.github.leogallego.ansiblejane.ui.jobs

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import io.github.leogallego.ansiblejane.model.Job
import io.github.leogallego.ansiblejane.presentation.jobs.RecentJobsUiState
import io.github.leogallego.ansiblejane.presentation.jobs.RecentJobsViewModel
import io.github.leogallego.ansiblejane.ui.components.DateFormatter
import io.github.leogallego.ansiblejane.ui.components.EmptyState
import io.github.leogallego.ansiblejane.ui.components.ErrorMessage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import io.github.leogallego.ansiblejane.ui.components.JobStatusBadge
import io.github.leogallego.ansiblejane.ui.components.SwipeAction
import io.github.leogallego.ansiblejane.ui.components.SwipeActionBox
import io.github.leogallego.ansiblejane.ui.components.LoadMoreIndicator
import io.github.leogallego.ansiblejane.ui.components.LoadingList
import io.github.leogallego.ansiblejane.ui.components.PaginationEffect
import io.github.leogallego.ansiblejane.ui.components.SearchBar
import io.github.leogallego.ansiblejane.ui.components.StatusFilterChips
import io.github.leogallego.ansiblejane.ui.components.pressScale
import org.jetbrains.compose.resources.stringResource
import aapremotecontrol.composeapp.generated.resources.*
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentJobsScreen(
    onNavigateToJobStatus: (Int) -> Unit,
    viewModel: RecentJobsViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        if (uiState is RecentJobsUiState.Success || uiState is RecentJobsUiState.Error) {
            isRefreshing = false
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        when (val state = uiState) {
            is RecentJobsUiState.Loading -> {
                LoadingList()
            }
            is RecentJobsUiState.Error -> {
                ErrorMessage(
                    error = state.error,
                    onRetry = { viewModel.loadRecentJobs() },
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            is RecentJobsUiState.Success -> {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        isRefreshing = true
                        viewModel.refresh()
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    val searchQuery by viewModel.searchQuery.collectAsState()

                    Column(modifier = Modifier.fillMaxSize()) {
                        SearchBar(
                            onSearch = { viewModel.search(it) },
                            placeholder = stringResource(Res.string.search_jobs),
                            initialQuery = searchQuery,
                        )

                        StatusFilterChips(
                            activeFilters = state.activeFilters,
                            onToggleFilter = { viewModel.toggleFilter(it) }
                        )

                        if (state.jobs.isEmpty()) {
                            EmptyState(
                                message = if (state.activeFilters.isNotEmpty()) {
                                    stringResource(Res.string.jobs_empty_filtered)
                                } else {
                                    stringResource(Res.string.jobs_empty)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            val listState = rememberLazyListState()

                            PaginationEffect(
                                listState = listState,
                                itemCount = state.jobs.size,
                                onLoadMore = { viewModel.loadMore() }
                            )

                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f)
                                    .testTag("list_jobs")
                            ) {
                                items(
                                    items = state.jobs,
                                    key = { it.id }
                                ) { job ->
                                    SwipeActionBox(
                                        endToStart = SwipeAction(
                                            icon = Icons.AutoMirrored.Filled.OpenInNew,
                                            label = stringResource(Res.string.swipe_view_details),
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                            onAction = { onNavigateToJobStatus(job.id) },
                                            removesItem = false,
                                        ),
                                        testTag = "swipe_job_${job.id}",
                                    ) {
                                        RecentJobItem(
                                            job = job,
                                            onClick = { onNavigateToJobStatus(job.id) }
                                        )
                                    }
                                }

                                if (state.isLoadingMore) {
                                    item { LoadMoreIndicator() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentJobItem(
    job: Job,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .pressScale(interactionSource)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = job.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = job.jobTemplateName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                job.started?.let {
                    Text(
                        text = DateFormatter.formatDateTime(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            JobStatusBadge(status = job.status)
        }
    }
}
