package io.github.leogallego.ansiblejane.assistant.local

import io.github.leogallego.ansiblejane.assistant.engine.DebugLog
import io.github.leogallego.ansiblejane.platform.IDeviceResources
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private val downloadJobLock = Any()
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
                LocalModelDownloadState.Error(modelId, LocalModelDownloadErrorKind.OTHER)
            return
        }

        val root = modelRoot()
        LocalModelFiles.ensureDirectory(root)

        val requiredBytes = model.sizeBytes + DISK_BUFFER_BYTES
        val freeBytes = deviceResources.freeDiskBytes(root)
        if (freeBytes < requiredBytes) {
            _downloadState.value = LocalModelDownloadState.Error(
                modelId,
                LocalModelDownloadErrorKind.DISK,
            )
            return
        }

        val job = synchronized(downloadJobLock) {
            downloadJob?.cancel()
            scope.launch {
                runDownload(model, root)
            }.also { downloadJob = it }
        }
        try {
            job.join()
        } finally {
            synchronized(downloadJobLock) {
                if (downloadJob === job) {
                    downloadJob = null
                }
            }
        }
    }

    override fun cancelDownload() {
        synchronized(downloadJobLock) {
            downloadJob?.cancel()
        }
    }

    override suspend fun delete(modelId: String) {
        val modelDir = LocalModelFiles.join(modelRoot(), modelId)
        LocalModelFiles.deleteRecursively(modelDir)
        if (_downloadState.value.let { it is LocalModelDownloadState.Succeeded && it.modelId == modelId }) {
            _downloadState.value = LocalModelDownloadState.Idle
        }
    }

    override suspend fun importFromPath(modelId: String, sourceAbsolutePath: String) {
        val model = catalog.find { it.id == modelId }
        if (model == null) {
            _downloadState.value =
                LocalModelDownloadState.Error(modelId, LocalModelDownloadErrorKind.OTHER)
            return
        }
        if (!LocalModelFiles.exists(sourceAbsolutePath)) {
            DebugLog.e(TAG, "Import source missing: $sourceAbsolutePath")
            _downloadState.value =
                LocalModelDownloadState.Error(modelId, LocalModelDownloadErrorKind.OTHER)
            return
        }

        val root = modelRoot()
        LocalModelFiles.ensureDirectory(root)
        val sourceLength = LocalModelFiles.length(sourceAbsolutePath)
        val requiredBytes = sourceLength + DISK_BUFFER_BYTES
        val freeBytes = deviceResources.freeDiskBytes(root)
        if (freeBytes < requiredBytes) {
            _downloadState.value = LocalModelDownloadState.Error(
                modelId,
                LocalModelDownloadErrorKind.DISK,
            )
            return
        }

        val targetPath = LocalModelFiles.join(root, model.id, model.fileName)
        val tempPath = "$targetPath.partial"
        LocalModelFiles.ensureDirectory(LocalModelFiles.join(root, model.id))
        LocalModelFiles.deleteRecursively(tempPath)

        _downloadState.value = LocalModelDownloadState.Downloading(
            modelId = model.id,
            bytesReceived = 0L,
            totalBytes = sourceLength.coerceAtLeast(1L),
        )

        try {
            val hasher = Sha256Hasher()
            var received = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val source = LocalModelFiles.openSource(sourceAbsolutePath)
            val sink = LocalModelFiles.openSink(tempPath, append = false)
            try {
                while (true) {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) continue
                    sink.write(buffer, 0, read)
                    hasher.update(buffer, 0, read)
                    received += read
                    _downloadState.value = LocalModelDownloadState.Downloading(
                        modelId = model.id,
                        bytesReceived = received,
                        totalBytes = sourceLength.coerceAtLeast(1L),
                    )
                }
            } finally {
                sink.close()
                source.close()
            }

            finalizeVerifiedFile(model, tempPath, targetPath, hasher.digestHex())
        } catch (e: CancellationException) {
            LocalModelFiles.deleteRecursively(tempPath)
            _downloadState.value = LocalModelDownloadState.Idle
            throw e
        } catch (e: Exception) {
            LocalModelFiles.deleteRecursively(tempPath)
            val kind = classifyTransferError(e)
            DebugLog.e(TAG, "Import failed for ${model.id} ($kind)", e)
            _downloadState.value = LocalModelDownloadState.Error(model.id, kind)
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
        LocalModelFiles.ensureDirectory(LocalModelFiles.join(root, model.id))

        var resumeFrom = if (LocalModelFiles.exists(tempPath)) {
            LocalModelFiles.length(tempPath)
        } else {
            0L
        }
        if (resumeFrom > model.sizeBytes) {
            DebugLog.w(TAG, "Partial larger than catalog size for ${model.id}; restarting")
            LocalModelFiles.deleteRecursively(tempPath)
            resumeFrom = 0L
        }

        var hasher = Sha256Hasher()
        if (resumeFrom > 0L) {
            hashExistingFile(tempPath, hasher)
        }

        _downloadState.value = LocalModelDownloadState.Downloading(
            modelId = model.id,
            bytesReceived = resumeFrom,
            totalBytes = model.sizeBytes,
        )

        if (resumeFrom == model.sizeBytes && resumeFrom > 0L) {
            finalizeVerifiedFile(model, tempPath, targetPath, hasher.digestHex())
            return
        }

        try {
            var received = resumeFrom
            httpClient.prepareGet(model.downloadUrl) {
                if (resumeFrom > 0L) {
                    header(HttpHeaders.Range, "bytes=$resumeFrom-")
                }
            }.execute { response ->
                val status = response.status
                var append = resumeFrom > 0L

                when {
                    status == HttpStatusCode.PartialContent && resumeFrom > 0L -> {
                        // Resume from existing bytes; hasher already covers them.
                    }
                    status.isSuccess() -> {
                        if (resumeFrom > 0L) {
                            DebugLog.w(
                                TAG,
                                "Server ignored Range for ${model.id} (HTTP ${status.value}); restarting",
                            )
                            LocalModelFiles.deleteRecursively(tempPath)
                            resumeFrom = 0L
                            received = 0L
                            append = false
                            hasher = Sha256Hasher()
                            _downloadState.value = LocalModelDownloadState.Downloading(
                                modelId = model.id,
                                bytesReceived = 0L,
                                totalBytes = model.sizeBytes,
                            )
                        }
                    }
                    else -> {
                        throw IllegalStateException(
                            "Download failed with HTTP ${status.value}",
                        )
                    }
                }

                val channel = response.bodyAsChannel()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val sink = LocalModelFiles.openSink(tempPath, append = append)
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

                finalizeVerifiedFile(model, tempPath, targetPath, hasher.digestHex())
            }
        } catch (e: CancellationException) {
            val kept = partialLength(tempPath)
            DebugLog.w(TAG, "Download cancelled for ${model.id}; keeping partial ($kept bytes)")
            _downloadState.value = LocalModelDownloadState.Idle
            throw e
        } catch (e: Exception) {
            val kind = classifyTransferError(e)
            DebugLog.e(TAG, "Download failed for ${model.id} ($kind)", e)
            if (kind == LocalModelDownloadErrorKind.HASH) {
                LocalModelFiles.deleteRecursively(tempPath)
            }
            _downloadState.value = LocalModelDownloadState.Error(model.id, kind)
        }
    }

    private fun finalizeVerifiedFile(
        model: LocalModel,
        tempPath: String,
        targetPath: String,
        actualSha: String,
    ) {
        if (!actualSha.equals(model.sha256, ignoreCase = true)) {
            LocalModelFiles.deleteRecursively(tempPath)
            DebugLog.e(TAG, "Hash mismatch for ${model.id}: $actualSha")
            _downloadState.value = LocalModelDownloadState.Error(
                model.id,
                LocalModelDownloadErrorKind.HASH,
            )
            return
        }
        LocalModelFiles.deleteRecursively(targetPath)
        LocalModelFiles.rename(tempPath, targetPath)
        _downloadState.value = LocalModelDownloadState.Succeeded(model.id)
    }

    private fun hashExistingFile(path: String, hasher: Sha256Hasher) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val source = LocalModelFiles.openSource(path)
        try {
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (read > 0) hasher.update(buffer, 0, read)
            }
        } finally {
            source.close()
        }
    }

    private fun partialLength(tempPath: String): Long =
        if (LocalModelFiles.exists(tempPath)) LocalModelFiles.length(tempPath) else 0L

    private fun modelRoot(): String = modelRootOverride ?: deviceResources.modelStorageDirectory()

    private fun absoluteModelPath(modelId: String): String? {
        val model = catalog.find { it.id == modelId } ?: return null
        return LocalModelFiles.join(modelRoot(), model.id, model.fileName)
    }

    companion object {
        private const val TAG = "LocalModelRepo"
        private const val DISK_BUFFER_BYTES = 500L * 1024 * 1024
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024

        internal fun classifyTransferError(error: Throwable): LocalModelDownloadErrorKind {
            val chain = generateSequence(error) { it.cause }.toList()
            for (e in chain) {
                val name = e::class.simpleName.orEmpty()
                val msg = e.message.orEmpty()
                when {
                    e is IllegalStateException &&
                        msg.startsWith("Download failed with HTTP") ->
                        return LocalModelDownloadErrorKind.NETWORK
                    name.contains("SocketTimeout", ignoreCase = true) ||
                        name.contains("HttpRequestTimeout", ignoreCase = true) ||
                        msg.contains("SocketTimeout", ignoreCase = true) ||
                        msg.contains("timed out", ignoreCase = true) ||
                        (
                            msg.contains("timeout", ignoreCase = true) &&
                                (
                                    name.contains("Timeout", ignoreCase = true) ||
                                        msg.contains("socket", ignoreCase = true) ||
                                        msg.contains("request", ignoreCase = true)
                                    )
                            ) ->
                        return LocalModelDownloadErrorKind.TIMEOUT
                    msg.contains("ENOSPC", ignoreCase = true) ||
                        msg.contains("No space", ignoreCase = true) ->
                        return LocalModelDownloadErrorKind.DISK
                    name.contains("UnknownHost", ignoreCase = true) ||
                        name.contains("ConnectException", ignoreCase = true) ||
                        name.contains("SSLException", ignoreCase = true) ||
                        name.contains("IOException", ignoreCase = true) ->
                        return LocalModelDownloadErrorKind.NETWORK
                }
            }
            return LocalModelDownloadErrorKind.OTHER
        }
    }
}
