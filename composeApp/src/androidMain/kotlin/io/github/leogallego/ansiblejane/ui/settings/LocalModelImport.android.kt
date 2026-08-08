package io.github.leogallego.ansiblejane.ui.settings

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberLocalModelImportController(
    onPreparing: () -> Unit,
    onResult: (LocalModelImportPick) -> Unit,
): LocalModelImportController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { PrepareState() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            onResult(LocalModelImportPick.Cancelled)
            return@rememberLauncherForActivityResult
        }
        state.job?.cancel()
        onPreparing()
        state.job = scope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    copyUriToCacheCancellable(context, uri)
                }
                if (path != null) {
                    onResult(LocalModelImportPick.Success(path))
                } else {
                    onResult(LocalModelImportPick.Failure)
                }
            } catch (_: CancellationException) {
                onResult(LocalModelImportPick.Cancelled)
            } finally {
                state.job = null
            }
        }
    }

    return remember(launcher, onPreparing, onResult) {
        object : LocalModelImportController {
            override fun launch() {
                launcher.launch(
                    arrayOf(
                        "application/octet-stream",
                        "*/*",
                    ),
                )
            }

            override fun cancelPrepare() {
                state.job?.cancel()
                state.job = null
            }
        }
    }
}

actual fun findCatalogModelInDownloads(fileName: String): String? {
    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val candidate = File(downloads, fileName)
    return if (candidate.isFile && candidate.canRead() && candidate.length() > 0L) {
        candidate.absolutePath
    } else {
        null
    }
}

private class PrepareState {
    var job: Job? = null
}

private suspend fun copyUriToCacheCancellable(context: Context, uri: Uri): String? {
    val cacheDir = File(context.cacheDir, "litert_import").apply { mkdirs() }
    val target = File(cacheDir, "import-${System.currentTimeMillis()}.litertlm")
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                }
            }
        } ?: return null
        if (!target.isFile || target.length() <= 0L) {
            target.delete()
            return null
        }
        target.absolutePath
    } catch (e: CancellationException) {
        target.delete()
        throw e
    } catch (_: Exception) {
        target.delete()
        null
    }
}
