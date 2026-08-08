package io.github.leogallego.ansiblejane.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@Composable
actual fun rememberLocalModelImportController(
    onPreparing: () -> Unit,
    onResult: (LocalModelImportPick) -> Unit,
): LocalModelImportController {
    val scope = rememberCoroutineScope()
    return remember(onPreparing, onResult) {
        object : LocalModelImportController {
            override fun launch() {
                scope.launch {
                    val pick = pickFileOnEdt()
                    if (pick is LocalModelImportPick.Success) {
                        onPreparing()
                    }
                    onResult(pick)
                }
            }

            override fun cancelPrepare() {
                // Desktop has no multi-GB prepare copy; dialog is modal.
            }
        }
    }
}

actual fun findCatalogModelInDownloads(fileName: String): String? {
    val home = System.getProperty("user.home") ?: return null
    val candidate = File(home, "Downloads${File.separator}$fileName")
    return if (candidate.isFile && candidate.canRead() && candidate.length() > 0L) {
        candidate.absolutePath
    } else {
        null
    }
}

private suspend fun pickFileOnEdt(): LocalModelImportPick =
    withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val show = Runnable {
                val chooser = JFileChooser().apply {
                    fileFilter = FileNameExtensionFilter(
                        "LiteRT models (*.litertlm)",
                        "litertlm",
                    )
                    isAcceptAllFileFilterUsed = true
                }
                val result = chooser.showOpenDialog(null)
                val pick = when {
                    result != JFileChooser.APPROVE_OPTION -> LocalModelImportPick.Cancelled
                    chooser.selectedFile?.isFile == true ->
                        LocalModelImportPick.Success(chooser.selectedFile.absolutePath)
                    else -> LocalModelImportPick.Failure
                }
                if (cont.isActive) cont.resume(pick)
            }
            if (SwingUtilities.isEventDispatchThread()) {
                show.run()
            } else {
                SwingUtilities.invokeLater(show)
            }
        }
    }
