package io.github.leogallego.ansiblejane.ui.templates

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.tooling.preview.PreviewLightDark
import io.github.leogallego.ansiblejane.model.JobTemplate
import io.github.leogallego.ansiblejane.model.JobTemplateSummaryFields
import io.github.leogallego.ansiblejane.model.Label
import io.github.leogallego.ansiblejane.model.LabelSummary
import io.github.leogallego.ansiblejane.model.UserCapabilities
import io.github.leogallego.ansiblejane.presentation.templates.LaunchState
import io.github.leogallego.ansiblejane.presentation.templates.TemplatesUiState
import io.github.leogallego.ansiblejane.presentation.templates.TemplatesViewModel
import io.github.leogallego.ansiblejane.ui.components.EmptyState
import io.github.leogallego.ansiblejane.ui.components.ErrorMessage
import io.github.leogallego.ansiblejane.ui.components.ExtraVarsDialog
import io.github.leogallego.ansiblejane.ui.components.LabelChips
import io.github.leogallego.ansiblejane.ui.components.LaunchConfirmDialog
import io.github.leogallego.ansiblejane.ui.components.LoadMoreIndicator
import io.github.leogallego.ansiblejane.ui.components.LoadingList
import io.github.leogallego.ansiblejane.ui.components.PaginationEffect
import io.github.leogallego.ansiblejane.ui.components.SearchBar
import io.github.leogallego.ansiblejane.ui.components.SwipeAction
import io.github.leogallego.ansiblejane.ui.components.SwipeActionBox
import io.github.leogallego.ansiblejane.ui.components.TemplateCard
import io.github.leogallego.ansiblejane.model.AppError
import io.github.leogallego.ansiblejane.ui.theme.AnsibleJaneTheme
import org.jetbrains.compose.resources.stringResource
import aapremotecontrol.composeapp.generated.resources.*
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateListScreen(
    onNavigateToJobStatus: (Int) -> Unit,
    viewModel: TemplatesViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val launchState by viewModel.launchState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val showFavoritesOnly by viewModel.showFavoritesOnly.collectAsState()
    var selectedLabel by remember { mutableStateOf<Label?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        if (uiState is TemplatesUiState.Success || uiState is TemplatesUiState.Error) {
            isRefreshing = false
        }
    }

    LaunchedEffect(launchState) {
        when (val state = launchState) {
            is LaunchState.Launched -> {
                viewModel.resetLaunchState()
                onNavigateToJobStatus(state.jobId)
            }
            is LaunchState.LaunchError -> {
                snackbarHostState.showSnackbar(state.error.message)
                viewModel.resetLaunchState()
            }
            else -> {}
        }
    }

    // Launch dialogs
    when (val state = launchState) {
        is LaunchState.Confirming -> {
            LaunchConfirmDialog(
                templateName = state.template.name,
                onConfirm = { viewModel.confirmLaunch() },
                onDismiss = { viewModel.cancelLaunch() }
            )
        }
        is LaunchState.EnteringVars -> {
            ExtraVarsDialog(
                templateName = state.template.name,
                onConfirm = { extraVars -> viewModel.confirmLaunch(extraVars) },
                onDismiss = { viewModel.cancelLaunch() }
            )
        }
        else -> {}
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchBar(
                onSearch = { viewModel.search(it) },
                initialQuery = searchQuery
            )

            when (val state = uiState) {
                is TemplatesUiState.Idle, is TemplatesUiState.Loading -> {
                    LoadingList()
                }
                is TemplatesUiState.Error -> {
                    ErrorMessage(
                        error = state.error,
                        onRetry = { viewModel.loadTemplates() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                is TemplatesUiState.Success -> {
                    LabelChips(
                        labels = state.availableLabels,
                        selectedLabel = selectedLabel,
                        onLabelSelected = { label ->
                            selectedLabel = label
                            viewModel.filterByLabel(label)
                        },
                        extraContent = {
                            if (favoriteIds.isNotEmpty()) {
                                FilterChip(
                                    selected = showFavoritesOnly,
                                    onClick = { viewModel.toggleShowFavoritesOnly() },
                                    label = { Text(stringResource(Res.string.templates_chip_favorites)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.Star,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    modifier = Modifier.testTag("chip_favorites")
                                )
                            }
                        }
                    )

                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            isRefreshing = true
                            viewModel.refresh()
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val displayTemplates = if (showFavoritesOnly) {
                            state.templates.filter { it.id in favoriteIds }
                        } else {
                            state.templates.sortedByDescending { it.id in favoriteIds }
                        }

                        if (displayTemplates.isEmpty()) {
                            EmptyState(
                                message = if (showFavoritesOnly) stringResource(Res.string.templates_empty_favorites) else stringResource(Res.string.templates_empty)
                            )
                        } else {
                            val listState = rememberLazyListState()

                            if (state.hasMore && !showFavoritesOnly) {
                                PaginationEffect(
                                    listState = listState,
                                    itemCount = displayTemplates.size,
                                    onLoadMore = { viewModel.loadMore() }
                                )
                            }

                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().testTag("list_templates")
                            ) {
                                items(
                                    items = displayTemplates,
                                    key = { it.id }
                                ) { template ->
                                    val isFavorite = template.id in favoriteIds
                                    SwipeActionBox(
                                        endToStart = SwipeAction(
                                            icon = if (isFavorite) Icons.Outlined.StarOutline else Icons.Filled.Star,
                                            label = if (isFavorite) {
                                                stringResource(Res.string.swipe_unfavorite)
                                            } else {
                                                stringResource(Res.string.swipe_favorite)
                                            },
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            onAction = { viewModel.toggleFavorite(template.id) },
                                            removesItem = false,
                                        ),
                                        testTag = "swipe_template_${template.id}",
                                    ) {
                                        TemplateCard(
                                            template = template,
                                            onClick = { viewModel.requestLaunch(template) },
                                            onLaunch = { viewModel.requestLaunch(template) },
                                            isFavorite = isFavorite,
                                            onToggleFavorite = { viewModel.toggleFavorite(template.id) },
                                            testTagPrefix = "button"
                                        )
                                    }
                                }

                                if (state.isLoadingMore && !showFavoritesOnly) {
                                    item { LoadMoreIndicator() }
                                }
                            }
                        }
                    }
                }
            }

            // Show loading overlay during launch
            if (launchState is LaunchState.Launching) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

private val previewTemplates = listOf(
    JobTemplate(
        id = 1,
        name = "Deploy Web Application",
        description = "Deploys the web application to production servers",
        summaryFields = JobTemplateSummaryFields(
            labels = LabelSummary(2, listOf(Label(1, "production"), Label(2, "deploy"))),
            userCapabilities = UserCapabilities(start = true)
        )
    ),
    JobTemplate(
        id = 2,
        name = "System Health Check",
        description = "Runs health checks across all managed hosts",
        summaryFields = JobTemplateSummaryFields(
            labels = LabelSummary(1, listOf(Label(3, "monitoring"))),
            userCapabilities = UserCapabilities(start = true)
        )
    ),
    JobTemplate(
        id = 3,
        name = "Patch Management",
        description = "Applies security patches and system updates",
        summaryFields = JobTemplateSummaryFields(
            userCapabilities = UserCapabilities(start = false)
        )
    ),
)

@PreviewLightDark
@Composable
private fun TemplateListLoadingPreview() {
    AnsibleJaneTheme(dynamicColor = false) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchBar(onSearch = {})
            LoadingList()
        }
    }
}

@PreviewLightDark
@Composable
private fun TemplateListEmptyPreview() {
    AnsibleJaneTheme(dynamicColor = false) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchBar(onSearch = {})
            EmptyState(message = "No templates available")
        }
    }
}

@PreviewLightDark
@Composable
private fun TemplateListContentPreview() {
    AnsibleJaneTheme(dynamicColor = false) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchBar(onSearch = {})
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    items = previewTemplates,
                    key = { it.id }
                ) { template ->
                    TemplateCard(
                        template = template,
                        onClick = {},
                        onLaunch = {}
                    )
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun TemplateListErrorPreview() {
    AnsibleJaneTheme(dynamicColor = false) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchBar(onSearch = {})
            ErrorMessage(error = AppError.Network(), onRetry = {})
        }
    }
}
