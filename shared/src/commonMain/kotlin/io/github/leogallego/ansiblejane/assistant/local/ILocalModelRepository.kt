package io.github.leogallego.ansiblejane.assistant.local

import kotlinx.coroutines.flow.StateFlow

interface ILocalModelRepository {
    val downloadState: StateFlow<LocalModelDownloadState>
    fun catalog(): List<LocalModel>
    fun isReady(modelId: String): Boolean
    fun modelPath(modelId: String): String?
    suspend fun download(modelId: String)
    fun cancelDownload()
    suspend fun delete(modelId: String)
    /**
     * Copy [sourceAbsolutePath] into Jane model storage, verify SHA-256 against the catalog,
     * and mark the model ready on success. Runs on the repository transfer job (cancellable).
     */
    suspend fun importFromPath(modelId: String, sourceAbsolutePath: String)
    /** Surface a picker/IO failure that never reached [importFromPath]. */
    fun notifyTransferError(modelId: String, kind: LocalModelDownloadErrorKind)
    /** Show Importing progress while a platform prepare/copy runs before [importFromPath]. */
    fun markImportPreparing(modelId: String)
    fun devicePerformance(modelId: String, contextTokens: Int): DevicePerformance
    fun hasAvx2Support(): Boolean
}

enum class LocalModelDownloadErrorKind {
    DISK,
    NETWORK,
    TIMEOUT,
    HASH,
    /** Picker/SAF could not read the selected file into a streamable path. */
    IMPORT,
    OTHER,
}

/** Transient download machine. After success or idle, use [ILocalModelRepository.isReady]. */
sealed interface LocalModelDownloadState {
    data object Idle : LocalModelDownloadState
    data class Downloading(
        val modelId: String,
        val bytesReceived: Long,
        val totalBytes: Long,
        /** True when copying/verifying a local file rather than downloading from the network. */
        val isImport: Boolean = false,
    ) : LocalModelDownloadState
    data class Succeeded(val modelId: String) : LocalModelDownloadState
    data class Error(
        val modelId: String,
        val kind: LocalModelDownloadErrorKind,
    ) : LocalModelDownloadState
}
