package io.github.leogallego.ansiblejane.assistant.local

import io.github.leogallego.ansiblejane.platform.IDeviceResources
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LocalModelRepository(
    private val deviceResources: IDeviceResources,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
    private val catalog: List<LocalModel> = LOCAL_MODEL_CATALOG,
    private val modelRootOverride: String? = null,
) : ILocalModelRepository {

    private val _downloadState =
        MutableStateFlow<LocalModelDownloadState>(LocalModelDownloadState.Idle)
    override val downloadState: StateFlow<LocalModelDownloadState> = _downloadState.asStateFlow()

    private val mutex = Mutex()
    private var downloadJob: Job? = null

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
            _downloadState.value =
                LocalModelDownloadState.Error(modelId, "Unknown model: $modelId")
            return
        }

        val root = modelRoot()
        LocalModelFiles.ensureDirectory(root)

        val requiredBytes = model.sizeBytes + DISK_BUFFER_BYTES
        val freeBytes = deviceResources.freeDiskBytes(root)
        if (freeBytes < requiredBytes) {
            _downloadState.value = LocalModelDownloadState.Error(
                modelId,
                "Insufficient disk space: need $requiredBytes bytes " +
                    "(model + ${DISK_BUFFER_BYTES / (1024 * 1024)} MB buffer), " +
                    "have $freeBytes bytes free",
            )
            return
        }

        val job = scope.launch {
            runDownload(model, root)
        }
        mutex.withLock {
            downloadJob?.cancel()
            downloadJob = job
        }
        try {
            job.join()
        } finally {
            mutex.withLock {
                if (downloadJob === job) {
                    downloadJob = null
                }
            }
        }
    }

    override fun cancelDownload() {
        downloadJob?.cancel()
    }

    override suspend fun delete(modelId: String) {
        val modelDir = LocalModelFiles.join(modelRoot(), modelId)
        LocalModelFiles.deleteRecursively(modelDir)
        if (_downloadState.value.let { it is LocalModelDownloadState.Succeeded && it.modelId == modelId }) {
            _downloadState.value = LocalModelDownloadState.Idle
        }
    }

    override fun devicePerformance(modelId: String, contextTokens: Int): DevicePerformance {
        val model = catalog.find { it.id == modelId }
            ?: return DevicePerformance.POOR
        val estimated = estimateGpuMemoryMb(model, contextTokens)
        return calculateDevicePerformance(deviceResources.totalMemoryBytes(), estimated)
    }

    override fun hasAvx2Support(): Boolean = deviceResources.hasAvx2Support()

    private suspend fun runDownload(model: LocalModel, root: String) {
        val targetPath = LocalModelFiles.join(root, model.id, model.fileName)
        val tempPath = "$targetPath.partial"
        LocalModelFiles.deleteRecursively(tempPath)
        LocalModelFiles.ensureDirectory(LocalModelFiles.join(root, model.id))

        _downloadState.value = LocalModelDownloadState.Downloading(
            modelId = model.id,
            bytesReceived = 0L,
            totalBytes = model.sizeBytes,
        )

        try {
            val hasher = Sha256Hasher()
            var received = 0L

            httpClient.prepareGet(model.downloadUrl).execute { response ->
                if (!response.status.isSuccess()) {
                    throw IllegalStateException(
                        "Download failed with HTTP ${response.status.value}",
                    )
                }
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val sink = LocalModelFiles.openSink(tempPath)
                try {
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read == -1) break
                        if (read > 0) {
                            sink.write(buffer, 0, read)
                            hasher.update(buffer, 0, read)
                            received += read
                            _downloadState.value = LocalModelDownloadState.Downloading(
                                modelId = model.id,
                                bytesReceived = received,
                                totalBytes = model.sizeBytes,
                            )
                        }
                    }
                } finally {
                    sink.close()
                }
            }

            val actualSha = hasher.digestHex()
            if (!actualSha.equals(model.sha256, ignoreCase = true)) {
                LocalModelFiles.deleteRecursively(tempPath)
                _downloadState.value = LocalModelDownloadState.Error(
                    model.id,
                    "SHA-256 mismatch: expected ${model.sha256}, got $actualSha",
                )
                return
            }

            LocalModelFiles.deleteRecursively(targetPath)
            LocalModelFiles.rename(tempPath, targetPath)

            _downloadState.value = LocalModelDownloadState.Succeeded(model.id)
        } catch (e: CancellationException) {
            LocalModelFiles.deleteRecursively(tempPath)
            _downloadState.value = LocalModelDownloadState.Idle
            throw e
        } catch (e: Exception) {
            LocalModelFiles.deleteRecursively(tempPath)
            _downloadState.value = LocalModelDownloadState.Error(
                model.id,
                e.message ?: "Download failed",
            )
        }
    }

    private fun modelRoot(): String = modelRootOverride ?: deviceResources.modelStorageDirectory()

    private fun absoluteModelPath(modelId: String): String? {
        val model = catalog.find { it.id == modelId } ?: return null
        return LocalModelFiles.join(modelRoot(), model.id, model.fileName)
    }

    companion object {
        private const val DISK_BUFFER_BYTES = 500L * 1024 * 1024
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}
