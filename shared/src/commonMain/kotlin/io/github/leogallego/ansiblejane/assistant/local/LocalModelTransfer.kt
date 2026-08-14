package io.github.leogallego.ansiblejane.assistant.local

import io.github.leogallego.ansiblejane.assistant.engine.DebugLog
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Exclusive download/import transfer owner for on-device LiteRT models.
 *
 * Owns the single transfer [Job], HTTP Range resume into `.partial`, SHA-256 verify,
 * import copy, progress updates, and cancel. Catalog/readiness stay on
 * [LocalModelRepository].
 */
internal class LocalModelTransfer(
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
) {
    private val _downloadState =
        MutableStateFlow<LocalModelDownloadState>(LocalModelDownloadState.Idle)
    val downloadState: StateFlow<LocalModelDownloadState> = _downloadState.asStateFlow()

    private val transferJobLock = Any()
    private var transferJob: Job? = null

    fun cancel() {
        val job = synchronized(transferJobLock) {
            val current = transferJob
            transferJob?.cancel()
            current
        }
        // SAF prepare copy runs outside transferJob but still shows Importing UI.
        if (job == null) {
            val state = _downloadState.value
            if (state is LocalModelDownloadState.Downloading && state.isImport) {
                _downloadState.value = LocalModelDownloadState.Idle
            }
        }
    }

    fun notifyTransferError(modelId: String, kind: LocalModelDownloadErrorKind) {
        _downloadState.value = LocalModelDownloadState.Error(modelId, kind)
    }

    fun markImportPreparing(modelId: String) {
        _downloadState.value = LocalModelDownloadState.Downloading(
            modelId = modelId,
            bytesReceived = 0L,
            totalBytes = 1L,
            isImport = true,
        )
    }

    fun clearSucceededIf(modelId: String) {
        if (_downloadState.value.let { it is LocalModelDownloadState.Succeeded && it.modelId == modelId }) {
            _downloadState.value = LocalModelDownloadState.Idle
        }
    }

    fun setError(modelId: String, kind: LocalModelDownloadErrorKind) {
        _downloadState.value = LocalModelDownloadState.Error(modelId, kind)
    }

    suspend fun download(model: LocalModel, root: String) {
        runExclusiveTransfer {
            runDownload(model, root)
        }
    }

    suspend fun importFromPath(
        model: LocalModel,
        root: String,
        sourceAbsolutePath: String,
        sourceLength: Long,
    ) {
        runExclusiveTransfer {
            runImport(model, root, sourceAbsolutePath, sourceLength)
        }
    }

    /** Bytes already on disk for a `.partial` file, clamped to [sizeBytes]. */
    fun clampedPartialLength(tempPath: String, sizeBytes: Long): Long {
        if (!LocalModelFiles.exists(tempPath)) return 0L
        val length = LocalModelFiles.length(tempPath)
        if (length > sizeBytes) {
            DebugLog.w(TAG, "Partial larger than catalog size; restarting")
            LocalModelFiles.deleteRecursively(tempPath)
            return 0L
        }
        return length
    }

    private suspend fun runExclusiveTransfer(block: suspend () -> Unit) {
        // Launch on the process-scoped [scope] so the transfer survives Settings
        // leaving (caller cancel). Clear [transferJob] only when *this* job
        // completes — never in a finally around join(), or cancelDownload()
        // loses the handle while the transfer keeps running (#492).
        val job = synchronized(transferJobLock) {
            transferJob?.cancel()
            scope.launch {
                try {
                    block()
                } finally {
                    val self = currentCoroutineContext()[Job]
                    synchronized(transferJobLock) {
                        if (transferJob === self) {
                            transferJob = null
                        }
                    }
                }
            }.also { transferJob = it }
        }
        job.join()
    }

    private suspend fun runImport(
        model: LocalModel,
        root: String,
        sourceAbsolutePath: String,
        sourceLength: Long,
    ) {
        val targetPath = LocalModelFiles.join(root, model.id, model.fileName)
        val tempPath = "$targetPath.partial"
        LocalModelFiles.ensureDirectory(LocalModelFiles.join(root, model.id))
        LocalModelFiles.deleteRecursively(tempPath)

        _downloadState.value = LocalModelDownloadState.Downloading(
            modelId = model.id,
            bytesReceived = 0L,
            totalBytes = sourceLength.coerceAtLeast(1L),
            isImport = true,
        )

        try {
            val hasher = Sha256Hasher()
            var received = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val source = LocalModelFiles.openSource(sourceAbsolutePath)
            val sink = LocalModelFiles.openSink(tempPath, append = false)
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
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
                        isImport = true,
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
            val classified = classifyTransferError(e)
            val kind = when (classified) {
                LocalModelDownloadErrorKind.DISK,
                LocalModelDownloadErrorKind.HASH,
                -> classified
                else -> LocalModelDownloadErrorKind.IMPORT
            }
            DebugLog.e(TAG, "Import failed for ${model.id} ($kind)", e)
            _downloadState.value = LocalModelDownloadState.Error(model.id, kind)
        } finally {
            maybeDeleteTransientImportSource(sourceAbsolutePath)
        }
    }

    private suspend fun runDownload(model: LocalModel, root: String) {
        val targetPath = LocalModelFiles.join(root, model.id, model.fileName)
        val tempPath = "$targetPath.partial"
        LocalModelFiles.ensureDirectory(LocalModelFiles.join(root, model.id))

        var resumeFrom = clampedPartialLength(tempPath, model.sizeBytes)

        var hasher = Sha256Hasher()
        if (resumeFrom > 0L) {
            hashExistingFile(tempPath, hasher)
        }

        _downloadState.value = LocalModelDownloadState.Downloading(
            modelId = model.id,
            bytesReceived = resumeFrom,
            totalBytes = model.sizeBytes,
            isImport = false,
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
                                isImport = false,
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
                        currentCoroutineContext().ensureActive()
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
                                isImport = false,
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

    private fun maybeDeleteTransientImportSource(path: String) {
        // Android SAF copies land under cacheDir/litert_import/ — drop after import attempt.
        if (path.contains("/litert_import/") || path.contains("\\litert_import\\")) {
            LocalModelFiles.deleteRecursively(path)
        }
    }

    companion object {
        private const val TAG = "LocalModelTransfer"
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
