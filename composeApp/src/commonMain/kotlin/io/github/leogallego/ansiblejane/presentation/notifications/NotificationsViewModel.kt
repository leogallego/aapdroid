package io.github.leogallego.ansiblejane.presentation.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.leogallego.ansiblejane.data.IWorkflowRepository
import io.github.leogallego.ansiblejane.model.WorkflowApproval
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NotificationsUiState(
    val approvals: List<WorkflowApproval> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val pendingCount: Int get() = approvals.size
}

class NotificationsViewModel(
    private val workflowRepository: IWorkflowRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    /** Session-local hide set so refresh cannot resurrect a swiped-away row. */
    private val dismissedIds = mutableSetOf<Int>()

    private var refreshJob: Job? = null
    private var lastFetchTime: Long = 0L

    init {
        refresh(clearSessionDismissals = false)
    }

    fun refreshIfStale(maxAgeMs: Long = 30_000L) {
        if (kotlin.time.Clock.System.now().toEpochMilliseconds() - lastFetchTime > maxAgeMs) {
            refresh(clearSessionDismissals = false)
        }
    }

    /**
     * Hide an approval from the sheet for this ViewModel session.
     * Survives stale/background refresh; an explicit [refresh] clears the hide set.
     */
    fun dismissApproval(approvalId: Int) {
        dismissedIds += approvalId
        _uiState.update { state ->
            state.copy(approvals = state.approvals.filterNot { it.id in dismissedIds })
        }
    }

    fun refresh(clearSessionDismissals: Boolean = true) {
        if (clearSessionDismissals) {
            dismissedIds.clear()
        }
        val oldJob = refreshJob
        refreshJob = viewModelScope.launch {
            oldJob?.cancelAndJoin()
            _uiState.update { it.copy(isLoading = true, error = null) }
            workflowRepository.getPendingApprovals(pageSize = 50).fold(
                onSuccess = { result ->
                    lastFetchTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
                    _uiState.update {
                        NotificationsUiState(
                            approvals = result.approvals.filterNot { it.id in dismissedIds },
                            isLoading = false,
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = e.message ?: "Failed to load approvals"
                        )
                    }
                }
            )
        }
    }
}
