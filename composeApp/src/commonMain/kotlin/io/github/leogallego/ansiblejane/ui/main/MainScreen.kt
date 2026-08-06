package io.github.leogallego.ansiblejane.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import io.github.leogallego.ansiblejane.assistant.data.KnownProvider
import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.presentation.main.MainViewModel
import io.github.leogallego.ansiblejane.presentation.notifications.NotificationsViewModel
import io.github.leogallego.ansiblejane.presentation.settings.SettingsTab
import io.github.leogallego.ansiblejane.ui.notifications.NotificationsSheet
import org.jetbrains.compose.resources.stringResource
import aapremotecontrol.composeapp.generated.resources.*
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSettings: (initialTab: String?) -> Unit = {},
    onNavigateToApproval: (Int) -> Unit = {},
    content: @Composable (TopLevelTab, Segment) -> Unit
) {
    val mainViewModel: MainViewModel = koinViewModel()
    val mainUiState by mainViewModel.uiState.collectAsState()
    val activeInstance = mainUiState.activeInstance
    val activeConfig = mainUiState.activeConfig
    val savedConfigs = mainUiState.savedConfigs
    val activeProviderKey = mainUiState.activeProviderKey
    val sessionTokens = mainUiState.sessionTokens
    val sessionTokensFormatted = mainUiState.sessionTokensFormatted

    val tabs = TopLevelTab.entries
    val dashboardTabIndex = tabs.indexOfFirst { it is TopLevelTab.Dashboard }.coerceAtLeast(0)
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(dashboardTabIndex) }
    val selectedTab = tabs[selectedTabIndex]

    val defaultSegmentIndex = selectedTab.segments.indexOfFirst { it.isDefault }.coerceAtLeast(0)
    var selectedSegmentIndices by rememberSaveable {
        mutableStateOf(mapOf<String, Int>())
    }
    val currentSegmentIndex = selectedSegmentIndices[selectedTab.route] ?: defaultSegmentIndex
    val currentSegment = selectedTab.segments[currentSegmentIndex]

    val notificationsViewModel: NotificationsViewModel = koinViewModel()
    val notificationsState by notificationsViewModel.uiState.collectAsState()
    var showNotificationsSheet by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    var showClearChatConfirm by remember { mutableStateOf(false) }
    var showProviderMenu by remember(selectedTabIndex) { mutableStateOf(false) }

    if (showClearChatConfirm) {
        AlertDialog(
            onDismissRequest = { showClearChatConfirm = false },
            title = { Text(stringResource(Res.string.clear_chat_title)) },
            text = { Text(stringResource(Res.string.clear_chat_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearChatConfirm = false
                    mainViewModel.clearHistory()
                }) { Text(stringResource(Res.string.btn_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatConfirm = false }) { Text(stringResource(Res.string.btn_cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut() using SizeTransform(clip = false)
                        },
                        label = "titleCrossfade"
                    ) { tab ->
                        if (tab is TopLevelTab.Assistant) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Assistant,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp)
                                )
                                activeInstance?.let { instance ->
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = instance.displayLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            Column {
                                Text(tab.label)
                                activeInstance?.let { instance ->
                                    Text(
                                        text = instance.displayLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    if (selectedTab is TopLevelTab.Assistant) {
                        Box {
                            IconButton(
                                onClick = { showProviderMenu = true },
                                modifier = Modifier.testTag("button_provider_menu")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AutoAwesome,
                                    contentDescription = stringResource(Res.string.cd_ai_model),
                                    tint = if (activeConfig is LlmProviderConfig.OpenAiCompatible &&
                                        (activeConfig as LlmProviderConfig.OpenAiCompatible).model.isNotBlank()
                                    )
                                        MaterialTheme.colorScheme.onSurface
                                    else
                                        MaterialTheme.colorScheme.error
                                )
                            }
                            ProviderDropdownMenu(
                                expanded = showProviderMenu,
                                onDismiss = { showProviderMenu = false },
                                activeProviderKey = activeProviderKey,
                                savedConfigs = savedConfigs,
                                sessionTokens = sessionTokens,
                                sessionTokensFormatted = sessionTokensFormatted,
                                onSwitchProvider = { key ->
                                    showProviderMenu = false
                                    mainViewModel.switchActiveProvider(key)
                                },
                                onNavigateToSettings = {
                                    showProviderMenu = false
                                    onNavigateToSettings(SettingsTab.AiProvider.name)
                                }
                            )
                        }
                        IconButton(
                            onClick = { showClearChatConfirm = true },
                            modifier = Modifier.testTag("button_clear_chat")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteSweep,
                                contentDescription = stringResource(Res.string.cd_clear_chat_history)
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            notificationsViewModel.refreshIfStale()
                            showNotificationsSheet = true
                        },
                        modifier = Modifier.testTag("button_notifications")
                    ) {
                        BadgedBox(
                            badge = {
                                val count = notificationsState.pendingCount
                                if (count > 0) {
                                    Badge { Text(count.toString()) }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = stringResource(Res.string.cd_notifications)
                            )
                        }
                    }
                    IconButton(
                        onClick = { onNavigateToSettings(null) },
                        modifier = Modifier.testTag("button_settings")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(Res.string.cd_settings)
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        modifier = Modifier.testTag("nav_${tab.route.substringAfterLast("/")}"),
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        icon = {
                            Icon(
                                imageVector = if (selectedTabIndex == index) tab.selectedIcon else tab.icon,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(
                                tab.label,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            if (selectedTab.segments.size > 1) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    selectedTab.segments.forEachIndexed { index, segment ->
                        SegmentedButton(
                            modifier = Modifier.testTag("segment_${segment.label.lowercase().replace(" ", "_")}"),
                            selected = currentSegmentIndex == index,
                            onClick = {
                                selectedSegmentIndices = selectedSegmentIndices +
                                    (selectedTab.route to index)
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = selectedTab.segments.size
                            )
                        ) {
                            Text(segment.label)
                        }
                    }
                }
            }

            Crossfade(
                targetState = "${selectedTab.route}/${currentSegment.label}",
                label = "segment_crossfade"
            ) { key ->
                // key used to trigger crossfade on tab/segment change
                content(selectedTab, currentSegment)
            }
        }
    }

    if (showNotificationsSheet) {
        NotificationsSheet(
            uiState = notificationsState,
            onDismiss = { showNotificationsSheet = false },
            onRefresh = { notificationsViewModel.refresh() },
            onApprovalClick = { approvalId ->
                showNotificationsSheet = false
                onNavigateToApproval(approvalId)
            },
            onDismissApproval = { approvalId ->
                notificationsViewModel.dismissApproval(approvalId)
            },
        )
    }
}

@Composable
private fun ProviderDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    activeProviderKey: String?,
    savedConfigs: Map<String, LlmProviderConfig>,
    sessionTokens: Int,
    sessionTokensFormatted: String,
    onSwitchProvider: (String) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val configuredProviders = savedConfigs.filter { (_, config) ->
        config is LlmProviderConfig.OpenAiCompatible && config.model.isNotBlank()
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp)
    ) {
        if (sessionTokens > 0) {
            val tokensCd = stringResource(Res.string.provider_tokens_session_cd, sessionTokens)
            Text(
                text = stringResource(Res.string.provider_tokens, sessionTokensFormatted),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("text_session_tokens")
                    .semantics {
                        contentDescription = tokensCd
                    }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }

        if (configuredProviders.isEmpty()) {
            Text(
                text = stringResource(Res.string.provider_no_providers),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        configuredProviders.forEach { (key, config) ->
            val isActive = key == activeProviderKey
            val provider = try {
                KnownProvider.valueOf(key)
            } catch (_: IllegalArgumentException) {
                KnownProvider.CUSTOM
            }
            val model = (config as? LlmProviderConfig.OpenAiCompatible)?.model ?: ""

            DropdownMenuItem(
                modifier = Modifier
                    .testTag("menu_provider_$key")
                    .then(
                        if (isActive) Modifier
                            .padding(horizontal = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                        else Modifier.padding(horizontal = 4.dp)
                    ),
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.widthIn(max = 240.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Text(
                                text = provider.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isActive)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = model,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isActive)
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                onClick = {
                    if (!isActive) onSwitchProvider(key) else onDismiss()
                }
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        DropdownMenuItem(
            text = {
                Text(
                    text = stringResource(Res.string.provider_configure),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            onClick = onNavigateToSettings
        )
    }
}
