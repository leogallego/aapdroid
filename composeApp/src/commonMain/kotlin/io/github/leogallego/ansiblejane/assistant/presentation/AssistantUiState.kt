package io.github.leogallego.ansiblejane.assistant.presentation

import androidx.compose.runtime.Immutable
import io.github.leogallego.ansiblejane.assistant.engine.ChatMessage
import io.github.leogallego.ansiblejane.assistant.engine.ToolUsage
import io.github.leogallego.ansiblejane.model.AppError
import io.github.leogallego.ansiblejane.model.McpConnectionState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred

data class PendingConfirmation(
    val toolName: String,
    val description: String,
    val continuation: CompletableDeferred<Boolean>
)

sealed interface AssistantUiState {
    data object Idle : AssistantUiState
    data object Loading : AssistantUiState
    @Immutable
    data class Active(
        val messages: ImmutableList<ChatMessage> = persistentListOf(),
        val isGenerating: Boolean = false,
        val streamingText: String? = null,
        /** Tool currently being queried, for read/write indicator on the streaming row. */
        val streamingTool: ToolUsage? = null,
        val connections: Map<String, McpConnectionState> = emptyMap(),
        val pendingConfirmation: PendingConfirmation? = null,
        val sessionTokens: Int = 0
    ) : AssistantUiState
    data class Error(val error: AppError) : AssistantUiState
}

