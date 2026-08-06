package io.github.leogallego.ansiblejane.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.leogallego.ansiblejane.assistant.data.IAssistantRepository
import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.assistant.engine.TokenUsage
import io.github.leogallego.ansiblejane.data.ITokenManager
import io.github.leogallego.ansiblejane.model.AapInstance
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Always-ready shell/top-bar state (no Idle/Loading/Error lifecycle).
 * Config types live in assistant.data as shared domain models.
 */
data class MainUiState(
    val activeInstance: AapInstance? = null,
    val activeConfig: LlmProviderConfig? = null,
    val savedConfigs: Map<String, LlmProviderConfig> = emptyMap(),
    val activeProviderKey: String? = null,
    val sessionTokens: Int = 0,
    val sessionTokensFormatted: String = "0",
)

class MainViewModel(
    tokenManager: ITokenManager,
    private val assistantRepository: IAssistantRepository,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = combine(
        tokenManager.activeInstance,
        assistantRepository.activeConfigFlow,
        assistantRepository.savedConfigsFlow,
        assistantRepository.activeProviderKeyFlow,
        assistantRepository.sessionTokensFlow,
    ) { activeInstance, activeConfig, savedConfigs, activeProviderKey, sessionTokens ->
        MainUiState(
            activeInstance = activeInstance,
            activeConfig = activeConfig,
            savedConfigs = savedConfigs,
            activeProviderKey = activeProviderKey,
            sessionTokens = sessionTokens,
            sessionTokensFormatted = TokenUsage.formatTokenCount(sessionTokens),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(activeInstance = tokenManager.activeInstance.value),
    )

    fun switchActiveProvider(key: String) {
        viewModelScope.launch {
            assistantRepository.switchActiveProvider(key)
        }
    }

    fun clearHistory() {
        assistantRepository.clearHistory()
    }
}
