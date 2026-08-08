package io.github.leogallego.ansiblejane.assistant.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.tooling.preview.PreviewLightDark
import io.github.leogallego.ansiblejane.assistant.engine.ChatMessage
import io.github.leogallego.ansiblejane.assistant.engine.ResponseSource
import io.github.leogallego.ansiblejane.assistant.engine.Role
import io.github.leogallego.ansiblejane.assistant.engine.ToolUsage
import io.github.leogallego.ansiblejane.assistant.presentation.AssistantUiState
import io.github.leogallego.ansiblejane.assistant.presentation.AssistantViewModel
import io.github.leogallego.ansiblejane.model.McpConnectionState
import io.github.leogallego.ansiblejane.ui.theme.AnsibleJaneTheme
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import aapremotecontrol.composeapp.generated.resources.*
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AssistantScreen(
    viewModel: AssistantViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        is AssistantUiState.Idle -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No active instance. Please log in first.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        is AssistantUiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        text = "Connecting to MCP servers...",
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        is AssistantUiState.Active -> {
            ActiveChatContent(
                state = state,
                onSendMessage = { viewModel.sendMessage(it) },
                onStopGeneration = { viewModel.stopGeneration() },
                onRegenerateLastMessage = { viewModel.regenerateLastMessage() },
                onConfirmApprove = { viewModel.confirmAction(true) },
                onConfirmDeny = { viewModel.confirmAction(false) },
                modifier = Modifier.fillMaxSize()
            )
        }

        is AssistantUiState.Error -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.error.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ActiveChatContent(
    state: AssistantUiState.Active,
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit,
    onRegenerateLastMessage: () -> Unit,
    onConfirmApprove: () -> Unit,
    onConfirmDeny: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var inputText by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }

    fun submit() {
        val text = inputText.text
        if (text.isNotBlank() && !state.isGenerating) {
            onSendMessage(text)
            inputText = TextFieldValue("")
        }
    }

    val imeVisible = isImeVisible()
    val messageCount = state.messages.size
    val totalItems = messageCount +
        (if (state.pendingConfirmation != null) 1 else 0) +
        (if (state.isGenerating) 1 else 0) +
        (if (messageCount == 0 && !state.isGenerating) 1 else 0)

    LaunchedEffect(messageCount, state.isGenerating, state.pendingConfirmation) {
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    LaunchedEffect(imeVisible) {
        if (imeVisible && totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val mcpStatusText = remember(state.connections) {
        if (state.connections.isEmpty()) null
        else {
            val connected = state.connections.count { it.value is McpConnectionState.Connected }
            "${connected}/${state.connections.size} MCP servers connected"
        }
    }

    Column(modifier = modifier.imePadding()) {
        mcpStatusText?.let { statusText ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val clipboardManager = LocalClipboardManager.current
        val lastAssistantId = remember(state.messages) {
            state.messages.lastOrNull { it.role == Role.ASSISTANT }?.id
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("list_chat_messages"),
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (state.messages.isEmpty() && state.streamingText == null) {
                item(key = "empty_placeholder") {
                    Box(
                        modifier = Modifier
                            .fillParentMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Hi, I'm Jane. I'm already connected and ready to help.\n\n" +
                                "Try asking me things like:\n" +
                                "- \"What job templates are available?\"\n" +
                                "- \"Show me the recent jobs\"\n" +
                                "- \"List the hosts in my inventory\"",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(
                items = state.messages,
                key = { it.id },
                contentType = { it.role }
            ) { message ->
                val isLastAssistant = message.role == Role.ASSISTANT &&
                    message.id == lastAssistantId &&
                    !state.isGenerating
                when (message.role) {
                    Role.USER -> UserBubble(
                        message = message,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(message.content))
                        },
                    )
                    Role.ASSISTANT -> AssistantMessage(
                        content = message.content,
                        source = message.source,
                        toolsUsed = message.toolsUsed,
                        tokenUsage = message.tokenUsage,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(message.content))
                        },
                        onRegenerate = if (isLastAssistant) onRegenerateLastMessage
                            else null,
                    )
                    else -> AssistantMessage(content = message.content)
                }
            }

            val confirmation = state.pendingConfirmation
            if (confirmation != null) {
                item(key = "confirmation", contentType = "confirmation") {
                    ConfirmationCard(
                        toolName = confirmation.toolName,
                        description = confirmation.description,
                        onApprove = onConfirmApprove,
                        onDeny = onConfirmDeny,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            if (state.isGenerating) {
                item(key = "streaming", contentType = "streaming") {
                    StreamingIndicator(
                        statusText = state.streamingText ?: stringResource(Res.string.assistant_thinking),
                        streamingTool = state.streamingTool,
                    )
                }
            }
        }

        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("field_assistant_input")
                .focusRequester(focusRequester)
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyUp &&
                        it.key == Key.Enter &&
                        it.isCtrlPressed
                    ) {
                        submit()
                        true
                    } else false
                },
            placeholder = { Text(stringResource(Res.string.assistant_input_placeholder)) },
            maxLines = 3,
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            trailingIcon = {
                if (state.isGenerating) {
                    FilledIconButton(
                        onClick = onStopGeneration,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("button_stop"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = stringResource(Res.string.cd_stop_generation),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    IconButton(
                        onClick = { submit() },
                        modifier = Modifier.testTag("button_send"),
                        enabled = inputText.text.isNotBlank(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(Res.string.cd_send_message),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun StreamingIndicator(
    statusText: String,
    streamingTool: ToolUsage? = null,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(modifier.width(8.dp))
        if (streamingTool != null) {
            ToolCallIndicator(tool = streamingTool)
            Spacer(modifier.width(8.dp))
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@PreviewLightDark
@Composable
private fun AssistantEmptyPreview() {
    AnsibleJaneTheme(dynamicColor = false) {
        ActiveChatContent(
            state = AssistantUiState.Active(),
            onSendMessage = {},
            onStopGeneration = {},
            onRegenerateLastMessage = {},
            onConfirmApprove = {},
            onConfirmDeny = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun AssistantWithMessagesPreview() {
    AnsibleJaneTheme(dynamicColor = false) {
        ActiveChatContent(
            state = AssistantUiState.Active(
                messages = persistentListOf(
                    ChatMessage(
                        role = Role.USER,
                        content = "What job templates are available?",
                    ),
                    ChatMessage(
                        role = Role.ASSISTANT,
                        content = "I found 3 job templates:\n\n" +
                            "1. **Deploy Web Application** - Deploys to production\n" +
                            "2. **System Health Check** - Runs health checks\n" +
                            "3. **Patch Management** - Applies security patches",
                        source = ResponseSource.MIXED,
                        toolsUsed = listOf(ToolUsage("list_job_templates"), ToolUsage("launch_job", isDestructive = true)),
                    ),
                    ChatMessage(
                        role = Role.USER,
                        content = "Launch the Deploy Web Application template",
                    ),
                ),
                isGenerating = true,
                streamingText = "Launching job template...",
            ),
            onSendMessage = {},
            onStopGeneration = {},
            onRegenerateLastMessage = {},
            onConfirmApprove = {},
            onConfirmDeny = {},
        )
    }
}
