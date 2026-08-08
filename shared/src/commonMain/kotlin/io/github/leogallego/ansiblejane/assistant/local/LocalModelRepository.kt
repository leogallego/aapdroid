package io.github.leogallego.ansiblejane.assistant.local

import io.github.leogallego.ansiblejane.assistant.engine.DebugLog
import io.github.leogallego.ansiblejane.platform.IDeviceResources
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Default [ILocalModelRepository].
 *
 * [scope] must be process-scoped (see interface KDoc / #478). Transfers run on [scope] via
 * exclusive jobs so cancel/resume survive Settings navigation; do not pass `viewModelScope`.
 */
class LocalModelRepository(
    private val deviceResources: IDeviceResources,
    httpClient: HttpClient,
    scope: CoroutineScope,
    private val catalog: List<LocalModel> = LOCAL_MODEL_CATALOG,
    private val modelRootOverride: String? = null,
) : ILocalModelRepository {

    private val transfer = LocalModelTransfer(httpClient, scope)

    override val downloadState: StateFlow<LocalModelDownloadState> = transfer.downloadState

    override fun catalog(): List<LocalModel> = catalog

    override fun isReady(modelId: String): Boolean {
        val path = absoluteModelPath(modelId) ?: return false
        return LocalModelFiles.exists(path) && LocalModelFiles.length(path) > 0L
    }

    override fun modelPath(modelId: String): String? {
        if (!isReady(modelId)) return null
        return absoluteModelPath(modelId)
    }

    override suspend fun download(modelId: String) {
        val model = catalog.find { it.id == modelId }
        if (model == null) {
            transfer.setError(modelId, LocalModelDownloadErrorKind.OTHER)
            return
        }

        val root = modelRoot()
        LocalModelFiles.ensureDirectory(root)
        val targetPath = LocalModelFiles.join(root, model.id, model.fileName)
        val tempPath = "$targetPath.partial"
        val resumeFrom = transfer.clampedPartialLength(tempPath, model.sizeBytes)
        val remainingBytes = (model.sizeBytes - resumeFrom).coerceAtLeast(0L)
        val requiredBytes = remainingBytes + DISK_BUFFER_BYTES
        val freeBytes = deviceResources.freeDiskBytes(root)
        if (freeBytes < requiredBytes) {
            transfer.setError(modelId, LocalModelDownloadErrorKind.DISK)
            return
        }

        transfer.download(model, root)
    }

    override fun cancelDownload() {
        transfer.cancel()
    }

    override suspend fun delete(modelId: String) {
        val modelDir = LocalModelFiles.join(modelRoot(), modelId)
        LocalModelFiles.deleteRecursively(modelDir)
        transfer.clearSucceededIf(modelId)
    }

    override suspend fun importFromPath(modelId: String, sourceAbsolutePath: String) {
        val model = catalog.find { it.id == modelId }
        if (model == null) {
            transfer.setError(modelId, LocalModelDownloadErrorKind.OTHER)
            return
        }
        if (!LocalModelFiles.exists(sourceAbsolutePath)) {
            DebugLog.e(TAG, "Import source missing: $sourceAbsolutePath")
            transfer.setError(modelId, LocalModelDownloadErrorKind.OTHER)
            return
        }

        val root = modelRoot()
        LocalModelFiles.ensureDirectory(root)
        val sourceLength = LocalModelFiles.length(sourceAbsolutePath)
        val requiredBytes = sourceLength + DISK_BUFFER_BYTES
        val freeBytes = deviceResources.freeDiskBytes(root)
        if (freeBytes < requiredBytes) {
            transfer.setError(modelId, LocalModelDownloadErrorKind.DISK)
            return
        }

        transfer.importFromPath(model, root, sourceAbsolutePath, sourceLength)
    }

    override fun notifyTransferError(modelId: String, kind: LocalModelDownloadErrorKind) {
        transfer.notifyTransferError(modelId, kind)
    }

    override fun markImportPreparing(modelId: String) {
        transfer.markImportPreparing(modelId)
    }

    override fun findExistingImportCandidate(modelId: String): String? {
        val model = catalog.find { it.id == modelId } ?: return null
        if (isReady(modelId)) return null
        return LocalModelFiles.findInUserDownloads(model.fileName)
    }

    override fun devicePerformance(modelId: String, contextTokens: Int): DevicePerformance {
        val model = catalog.find { it.id == modelId }
            ?: return DevicePerformance.POOR
        val estimated = estimateGpuMemoryMb(model, contextTokens)
        return calculateDevicePerformance(deviceResources.totalMemoryBytes(), estimated)
    }

    override fun hasAvx2Support(): Boolean = deviceResources.hasAvx2Support()

    private fun modelRoot(): String = modelRootOverride ?: deviceResources.modelStorageDirectory()

    private fun absoluteModelPath(modelId: String): String? {
        val model = catalog.find { it.id == modelId } ?: return null
        return LocalModelFiles.join(modelRoot(), model.id, model.fileName)
    }

    companion object {
        private const val TAG = "LocalModelRepo"
        private const val DISK_BUFFER_BYTES = 500L * 1024 * 1024

        /**
         * Kept for existing call sites/tests; delegates to [LocalModelTransfer].
         */
        internal fun classifyTransferError(error: Throwable): LocalModelDownloadErrorKind =
            LocalModelTransfer.classifyTransferError(error)
    }
}
