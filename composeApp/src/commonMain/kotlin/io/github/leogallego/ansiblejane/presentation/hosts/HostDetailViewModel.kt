package io.github.leogallego.ansiblejane.presentation.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.leogallego.ansiblejane.data.IHostRepository
import io.github.leogallego.ansiblejane.model.JobHostSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

sealed interface HostDetailUiState {
    /**
     * Detail content with independent section loading flags.
     * Repository failures surface as empty sections (same UX as the former UI-layer fetch).
     */
    data class Content(
        val factsLoading: Boolean = true,
        val facts: Map<String, JsonElement> = emptyMap(),
        val jobsLoading: Boolean = true,
        val jobSummaries: List<JobHostSummary> = emptyList(),
    ) : HostDetailUiState
}

class HostDetailViewModel(
    private val hostId: Int,
    private val hostRepository: IHostRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HostDetailUiState>(HostDetailUiState.Content())
    val uiState: StateFlow<HostDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (hostId <= 0) {
            _uiState.update {
                HostDetailUiState.Content(
                    factsLoading = false,
                    jobsLoading = false,
                )
            }
            return
        }
        _uiState.update { HostDetailUiState.Content() }
        viewModelScope.launch {
            hostRepository.getHostFacts(hostId).fold(
                onSuccess = { facts ->
                    _uiState.update { state ->
                        val content = state as? HostDetailUiState.Content ?: HostDetailUiState.Content()
                        content.copy(factsLoading = false, facts = facts)
                    }
                },
                onFailure = {
                    _uiState.update { state ->
                        val content = state as? HostDetailUiState.Content ?: HostDetailUiState.Content()
                        content.copy(factsLoading = false, facts = emptyMap())
                    }
                }
            )

            hostRepository.getHostJobSummaries(hostId).fold(
                onSuccess = { result ->
                    _uiState.update { state ->
                        val content = state as? HostDetailUiState.Content ?: HostDetailUiState.Content()
                        content.copy(jobsLoading = false, jobSummaries = result.summaries)
                    }
                },
                onFailure = {
                    _uiState.update { state ->
                        val content = state as? HostDetailUiState.Content ?: HostDetailUiState.Content()
                        content.copy(jobsLoading = false, jobSummaries = emptyList())
                    }
                }
            )
        }
    }

    fun retry() {
        load()
    }
}
