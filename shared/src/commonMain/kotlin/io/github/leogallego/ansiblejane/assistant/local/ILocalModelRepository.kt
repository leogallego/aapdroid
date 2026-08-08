package io.github.leogallego.ansiblejane.assistant.local

import kotlinx.coroutines.flow.StateFlow

/**
 * On-device LiteRT model catalog, readiness, and multi-GB transfer orchestration (#264 / #469).
 *
 * **Transfer scope:** implementations that perform download/import own a **process-scoped**
 * coroutine scope (Koin singleton — not `viewModelScope`). Multi-GB transfers must survive
 * leaving Settings; callers cancel via [cancelDownload], not by clearing the ViewModel.
 * A future `@Named("localModelTransfers")` injected scope is fine if it stays process-scoped.
 */
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
    /**
     * Best-effort absolute path to a catalog [modelId] file already present under the user
     * Downloads folder (readable, non-empty). Null when unknown/ready/missing. May touch the
     * filesystem — call off the main thread when used from UI.
     */
    fun findExistingImportCandidate(modelId: String): String?
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
