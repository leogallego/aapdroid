package io.github.leogallego.ansiblejane.fakes

import io.github.leogallego.ansiblejane.assistant.local.DevicePerformance
import io.github.leogallego.ansiblejane.assistant.local.ILocalModelRepository
import io.github.leogallego.ansiblejane.assistant.local.LOCAL_MODEL_CATALOG
import io.github.leogallego.ansiblejane.assistant.local.LocalModel
import io.github.leogallego.ansiblejane.assistant.local.LocalModelDownloadErrorKind
import io.github.leogallego.ansiblejane.assistant.local.LocalModelDownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeLocalModelRepository(
    private val models: List<LocalModel> = LOCAL_MODEL_CATALOG,
) : ILocalModelRepository {
    private val _downloadState =
        MutableStateFlow<LocalModelDownloadState>(LocalModelDownloadState.Idle)
    override val downloadState: StateFlow<LocalModelDownloadState> = _downloadState.asStateFlow()

    var cancelCount: Int = 0
        private set

    fun emit(state: LocalModelDownloadState) {
        _downloadState.value = state
    }

    override fun catalog(): List<LocalModel> = models

    override fun isReady(modelId: String): Boolean = false

    override fun modelPath(modelId: String): String? = null

    override suspend fun download(modelId: String) = Unit

    override fun cancelDownload() {
        cancelCount++
        _downloadState.value = LocalModelDownloadState.Idle
    }

    override suspend fun delete(modelId: String) = Unit

    override suspend fun importFromPath(modelId: String, sourceAbsolutePath: String) = Unit

    override fun notifyTransferError(modelId: String, kind: LocalModelDownloadErrorKind) {
        _downloadState.value = LocalModelDownloadState.Error(modelId, kind)
    }

    override fun markImportPreparing(modelId: String) {
        _downloadState.value = LocalModelDownloadState.Downloading(
            modelId = modelId,
            bytesReceived = 0L,
            totalBytes = 0L,
            isImport = true,
        )
    }

    override fun findExistingImportCandidate(modelId: String): String? = null

    override fun devicePerformance(modelId: String, contextTokens: Int): DevicePerformance =
        DevicePerformance.OK

    override fun hasAvx2Support(): Boolean = true
}
