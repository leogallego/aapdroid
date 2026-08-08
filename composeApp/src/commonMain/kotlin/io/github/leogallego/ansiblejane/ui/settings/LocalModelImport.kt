package io.github.leogallego.ansiblejane.ui.settings

import androidx.compose.runtime.Composable

/**
 * Platform file picker for `.litertlm` import (#469).
 * Invoking the returned lambda launches the picker; [onPickedAbsolutePath] receives a
 * filesystem path Jane can stream-copy (Android may copy the SAF Uri into cache first).
 */
@Composable
expect fun rememberLocalModelImportLauncher(
    onPickedAbsolutePath: (absolutePath: String) -> Unit,
): () -> Unit

/**
 * Best-effort lookup of a catalog filename under the user Downloads folder.
 * Returns an absolute path when readable; null when missing or inaccessible.
 */
expect fun findCatalogModelInDownloads(fileName: String): String?
