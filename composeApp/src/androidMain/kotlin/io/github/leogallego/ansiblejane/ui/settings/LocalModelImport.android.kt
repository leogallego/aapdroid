package io.github.leogallego.ansiblejane.ui.settings

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.FileOutputStream

@Composable
actual fun rememberLocalModelImportLauncher(
    onPickedAbsolutePath: (absolutePath: String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = copyUriToCache(context, uri) ?: return@rememberLauncherForActivityResult
        onPickedAbsolutePath(path)
    }
    return remember(launcher) {
        {
            launcher.launch(
                arrayOf(
                    "application/octet-stream",
                    "*/*",
                ),
            )
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

private fun copyUriToCache(context: Context, uri: Uri): String? {
    return try {
        val cacheDir = File(context.cacheDir, "litert_import").apply { mkdirs() }
        val target = File(cacheDir, "import-${System.currentTimeMillis()}.litertlm")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        } ?: return null
        target.absolutePath
    } catch (_: Exception) {
        null
    }
}
