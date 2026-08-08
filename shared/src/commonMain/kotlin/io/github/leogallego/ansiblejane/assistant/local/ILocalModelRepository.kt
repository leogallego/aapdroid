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
    fun devicePerformance(modelId: String, contextTokens: Int): DevicePerformance
    fun hasAvx2Support(): Boolean
}

enum class LocalModelDownloadErrorKind {
    DISK,
    NETWORK,
    HASH,
    OTHER,
}

/** Transient download machine. After success or idle, use [ILocalModelRepository.isReady]. */
sealed interface LocalModelDownloadState {
    data object Idle : LocalModelDownloadState
    data class Downloading(
        val modelId: String,
        val bytesReceived: Long,
        val totalBytes: Long,
    ) : LocalModelDownloadState
    data class Succeeded(val modelId: String) : LocalModelDownloadState
    data class Error(
        val modelId: String,
        val kind: LocalModelDownloadErrorKind,
    ) : LocalModelDownloadState
}
