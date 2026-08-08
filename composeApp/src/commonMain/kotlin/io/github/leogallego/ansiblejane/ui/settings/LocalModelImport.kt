package io.github.leogallego.ansiblejane.ui.settings

import androidx.compose.runtime.Composable

/** Result of a platform `.litertlm` file pick (#469). */
sealed interface LocalModelImportPick {
    data class Success(val absolutePath: String) : LocalModelImportPick
    data object Failure : LocalModelImportPick
    data object Cancelled : LocalModelImportPick
}

/** Launches a pick/prepare flow and can cancel an in-flight SAF/cache copy. */
interface LocalModelImportController {
    fun launch()
    fun cancelPrepare()
}

/**
 * Platform file picker for `.litertlm` import.
 * [onPreparing] fires before any multi-GB copy so the UI can show Importing + Cancel.
 * Heavy copy work must run off the main thread and be abortable via [LocalModelImportController.cancelPrepare].
 */
@Composable
expect fun rememberLocalModelImportController(
    onPreparing: () -> Unit,
    onResult: (LocalModelImportPick) -> Unit,
): LocalModelImportController

/**
 * Best-effort lookup of a catalog filename under the user Downloads folder.
 * Call from a background dispatcher — may touch the filesystem.
 */
expect fun findCatalogModelInDownloads(fileName: String): String?
