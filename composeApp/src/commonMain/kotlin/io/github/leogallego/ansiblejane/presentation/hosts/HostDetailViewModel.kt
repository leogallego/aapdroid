package io.github.leogallego.ansiblejane.presentation.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.leogallego.ansiblejane.data.IHostRepository
import io.github.leogallego.ansiblejane.model.JobHostSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    data class Success(
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

    private val _uiState = MutableStateFlow<HostDetailUiState>(HostDetailUiState.Success())
    val uiState: StateFlow<HostDetailUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        if (hostId <= 0) {
            _uiState.update {
                HostDetailUiState.Success(
                    factsLoading = false,
                    jobsLoading = false,
                )
            }
            return
        }
        _uiState.update { HostDetailUiState.Success() }
        loadJob = viewModelScope.launch {
            coroutineScope {
                val factsDeferred = async {
                    hostRepository.getHostFacts(hostId).fold(
                        onSuccess = { it },
                        onFailure = { emptyMap() },
                    )
                }
                val jobsDeferred = async {
                    hostRepository.getHostJobSummaries(hostId).fold(
                        onSuccess = { it.summaries },
                        onFailure = { emptyList() },
                    )
                }

                val facts = factsDeferred.await()
                _uiState.update { state ->
                    val success = state as HostDetailUiState.Success
                    success.copy(factsLoading = false, facts = facts)
                }

                val jobSummaries = jobsDeferred.await()
                _uiState.update { state ->
                    val success = state as HostDetailUiState.Success
                    success.copy(jobsLoading = false, jobSummaries = jobSummaries)
                }
            }
        }
    }

    fun retry() {
        load()
    }
}
