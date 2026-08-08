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
    readyIds: Set<String> = emptySet(),
    private val hasAvx2: Boolean = true,
    private val performance: DevicePerformance = DevicePerformance.GOOD,
) : ILocalModelRepository {
    private val ready = readyIds.toMutableSet()
    private val _downloadState = MutableStateFlow<LocalModelDownloadState>(LocalModelDownloadState.Idle)
    override val downloadState: StateFlow<LocalModelDownloadState> = _downloadState.asStateFlow()

    var downloadCalls: List<String> = emptyList()
        private set
    var cancelCalls: Int = 0
        private set
    var deleteCalls: List<String> = emptyList()
        private set
    var importCalls: List<Pair<String, String>> = emptyList()
        private set

    override fun catalog(): List<LocalModel> = models

    override fun isReady(modelId: String): Boolean = modelId in ready

    override fun modelPath(modelId: String): String? =
        if (modelId in ready) "/fake/models/$modelId.litertlm" else null

    override suspend fun download(modelId: String) {
        downloadCalls = downloadCalls + modelId
        ready.add(modelId)
        _downloadState.value = LocalModelDownloadState.Succeeded(modelId)
    }

    override fun cancelDownload() {
        cancelCalls += 1
        _downloadState.value = LocalModelDownloadState.Idle
    }

    override suspend fun delete(modelId: String) {
        deleteCalls = deleteCalls + modelId
        ready.remove(modelId)
        if (_downloadState.value.let { it is LocalModelDownloadState.Succeeded && it.modelId == modelId }) {
            _downloadState.value = LocalModelDownloadState.Idle
        }
    }

    override suspend fun importFromPath(modelId: String, sourceAbsolutePath: String) {
        importCalls = importCalls + (modelId to sourceAbsolutePath)
        ready.add(modelId)
        _downloadState.value = LocalModelDownloadState.Succeeded(modelId)
    }

    override fun devicePerformance(modelId: String, contextTokens: Int): DevicePerformance =
        performance

    override fun hasAvx2Support(): Boolean = hasAvx2

    fun setDownloadState(state: LocalModelDownloadState) {
        _downloadState.value = state
    }
}
