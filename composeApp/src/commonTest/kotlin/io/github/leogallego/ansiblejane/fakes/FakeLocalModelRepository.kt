package io.github.leogallego.ansiblejane.fakes

import io.github.leogallego.ansiblejane.assistant.local.DevicePerformance
import io.github.leogallego.ansiblejane.assistant.local.ILocalModelRepository
import io.github.leogallego.ansiblejane.assistant.local.LOCAL_MODEL_CATALOG
import io.github.leogallego.ansiblejane.assistant.local.LocalModel
import io.github.leogallego.ansiblejane.assistant.local.LocalModelDownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeLocalModelRepository(
    private val models: List<LocalModel> = LOCAL_MODEL_CATALOG,
    private val readyIds: Set<String> = emptySet(),
) : ILocalModelRepository {
    private val _downloadState = MutableStateFlow<LocalModelDownloadState>(LocalModelDownloadState.Idle)
    override val downloadState: StateFlow<LocalModelDownloadState> = _downloadState.asStateFlow()

    override fun catalog(): List<LocalModel> = models

    override fun isReady(modelId: String): Boolean = modelId in readyIds

    override fun modelPath(modelId: String): String? =
        if (modelId in readyIds) "/fake/models/$modelId.litertlm" else null

    override suspend fun download(modelId: String) {
        _downloadState.value = LocalModelDownloadState.Succeeded(modelId)
    }

    override fun cancelDownload() {
        _downloadState.value = LocalModelDownloadState.Idle
    }

    override suspend fun delete(modelId: String) = Unit

    override fun devicePerformance(modelId: String, contextTokens: Int): DevicePerformance =
        DevicePerformance.GOOD

    override fun hasAvx2Support(): Boolean = true
}
