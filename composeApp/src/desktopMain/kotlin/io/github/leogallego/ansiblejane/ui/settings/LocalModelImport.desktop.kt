package io.github.leogallego.ansiblejane.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberLocalModelImportLauncher(
    onPickedAbsolutePath: (absolutePath: String) -> Unit,
): () -> Unit {
    return remember(onPickedAbsolutePath) {
        {
            val chooser = JFileChooser().apply {
                fileFilter = FileNameExtensionFilter("LiteRT models (*.litertlm)", "litertlm")
                isAcceptAllFileFilterUsed = true
            }
            val result = chooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) {
                val file = chooser.selectedFile
                if (file != null && file.isFile) {
                    onPickedAbsolutePath(file.absolutePath)
                }
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
