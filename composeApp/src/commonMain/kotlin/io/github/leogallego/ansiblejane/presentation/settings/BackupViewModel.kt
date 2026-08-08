package io.github.leogallego.ansiblejane.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.leogallego.ansiblejane.assistant.data.IAssistantRepository
import io.github.leogallego.ansiblejane.data.ITokenManager
import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.data.backup.BackupDecryptionException
import io.github.leogallego.ansiblejane.data.backup.BackupEnvelope
import io.github.leogallego.ansiblejane.data.backup.BackupInstance
import io.github.leogallego.ansiblejane.data.backup.BackupManager
import io.github.leogallego.ansiblejane.model.ApiVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ImportMode { MERGE, REPLACE }

sealed interface BackupUiState {
    data object Idle : BackupUiState
    data object Exporting : BackupUiState
    data class ExportReady(val data: ByteArray) : BackupUiState
    data class ImportPreview(
        val envelope: BackupEnvelope,
        val duplicateCount: Int,
        val newCount: Int,
        val instances: List<BackupInstance>,
        val llmConfig: LlmProviderConfig?,
        val hasExistingInstances: Boolean
    ) : BackupUiState
    data object Importing : BackupUiState
    data class Success(val message: String) : BackupUiState
    data class Error(val message: String) : BackupUiState
}

class BackupViewModel(
    private val backupManager: BackupManager,
    private val tokenManager: ITokenManager,
    private val assistantRepository: IAssistantRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    private var pendingEnvelope: BackupEnvelope? = null

    fun exportBackup(password: String, includeAssistantConfig: Boolean) {
        viewModelScope.launch {
            _uiState.update { BackupUiState.Exporting }
            try {
                val instances = tokenManager.instances.value
                if (instances.isEmpty()) {
                    _uiState.update { BackupUiState.Error("No instances to export") }
                    return@launch
                }
                val llmConfig = if (includeAssistantConfig) assistantRepository.loadLlmConfig() else null
                val allConfigs = if (includeAssistantConfig) assistantRepository.loadAllLlmConfigs() else null
                val data = backupManager.exportBackup(password, instances, llmConfig, allConfigs)
                _uiState.update { BackupUiState.ExportReady(data) }
            } catch (e: Exception) {
                _uiState.update { BackupUiState.Error("Export failed: ${e.message}") }
            }
        }
    }

    fun startImport(data: ByteArray, password: String) {
        viewModelScope.launch {
            _uiState.update { BackupUiState.Importing }
            try {
                val envelope = backupManager.importBackup(data, password)
                val existingUrls = tokenManager.instances.value
                    .map { it.baseUrl.trimEnd('/').lowercase() }
                    .toSet()

                var duplicates = 0
                var newOnes = 0
                for (inst in envelope.instances) {
                    if (inst.baseUrl.trimEnd('/').lowercase() in existingUrls) duplicates++ else newOnes++
                }

                pendingEnvelope = envelope
                _uiState.update {
                    BackupUiState.ImportPreview(
                        envelope = envelope,
                        duplicateCount = duplicates,
                        newCount = newOnes,
                        instances = envelope.instances,
                        llmConfig = envelope.llmConfig,
                        hasExistingInstances = existingUrls.isNotEmpty()
                    )
                }
            } catch (e: BackupDecryptionException) {
                _uiState.update { BackupUiState.Error(e.message ?: "Decryption failed") }
            } catch (e: Exception) {
                _uiState.update { BackupUiState.Error("Import failed: ${e.message}") }
            }
        }
    }

    fun confirmImport(mode: ImportMode) {
        val envelope = pendingEnvelope ?: return
        viewModelScope.launch {
            _uiState.update { BackupUiState.Importing }
            try {
                // Validate all import data before mutating state
                val validatedInstances = envelope.instances.map { inst ->
                    val apiVersion = try {
                        ApiVersion.valueOf(inst.apiVersion)
                    } catch (_: Exception) {
                        ApiVersion.CONTROLLER_V2
                    }
                    inst to apiVersion
                }

                val existingUrls = tokenManager.instances.value
                    .map { it.baseUrl.trimEnd('/').lowercase() }
                    .toSet()

                // All validation passed — now safe to clear and write
                if (mode == ImportMode.REPLACE) {
                    tokenManager.clearCredentials()
                }

                var imported = 0
                for ((inst, apiVersion) in validatedInstances) {
                    val normalizedUrl = inst.baseUrl.trimEnd('/').lowercase()
                    if (mode == ImportMode.MERGE && normalizedUrl in existingUrls) continue

                    tokenManager.saveInstance(
                        baseUrl = inst.baseUrl,
                        token = inst.token,
                        alias = inst.alias,
                        apiVersion = apiVersion,
                        trustSelfSigned = inst.trustSelfSigned,
                        certFingerprint = inst.certFingerprint
                    )

                    if (inst.mcpEnabled || !inst.mcpServerUrls.isNullOrEmpty()) {
                        val savedInstances = tokenManager.instances.value
                        val savedId = savedInstances.find {
                            it.baseUrl.trimEnd('/').lowercase() == normalizedUrl
                        }?.id
                        if (savedId != null) {
                            tokenManager.updateMcpConfig(savedId, inst.mcpEnabled, inst.mcpServerUrls)
                        }
                    }
                    imported++
                }

                val configs = envelope.llmConfigs
                if (!configs.isNullOrEmpty()) {
                    assistantRepository.saveAllLlmConfigs(configs)
                    val activeConfig = envelope.activeProvider?.let { configs[it] }
                        ?: configs.values.firstOrNull()
                    activeConfig?.let { assistantRepository.saveLlmConfig(it) }
                } else {
                    envelope.llmConfig?.let { config ->
                        assistantRepository.saveLlmConfig(config)
                    }
                }

                val llmNote = if (!envelope.llmConfigs.isNullOrEmpty() || envelope.llmConfig != null) " + LLM config" else ""
                pendingEnvelope = null
                _uiState.update { BackupUiState.Success("Imported $imported instance(s)$llmNote") }
            } catch (e: Exception) {
                _uiState.update { BackupUiState.Error("Import failed: ${e.message}") }
            }
        }
    }

    fun dismiss() {
        pendingEnvelope = null
        _uiState.update { BackupUiState.Idle }
    }
}
