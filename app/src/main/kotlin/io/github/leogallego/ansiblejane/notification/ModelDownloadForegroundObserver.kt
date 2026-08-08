package io.github.leogallego.ansiblejane.notification

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.leogallego.ansiblejane.assistant.local.ILocalModelRepository
import io.github.leogallego.ansiblejane.assistant.local.LocalModelDownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Bridges [ILocalModelRepository.downloadState] to [ModelDownloadForegroundService].
 *
 * Starts the FGS only for network catalog downloads (`Downloading && !isImport`).
 * Import/SAF transfers stay in-process without a foreground service.
 */
class ModelDownloadForegroundObserver(
    private val appContext: Context,
    private val repository: ILocalModelRepository,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            repository.downloadState
                .map { state ->
                    state is LocalModelDownloadState.Downloading && !state.isImport
                }
                .distinctUntilChanged()
                .collect { networkDownloadActive ->
                    if (networkDownloadActive) {
                        startService()
                    } else {
                        stopService()
                    }
                }
        }
    }

    private fun startService() {
        try {
            val intent = Intent(appContext, ModelDownloadForegroundService::class.java)
            ContextCompat.startForegroundService(appContext, intent)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start model download FGS", e)
        }
    }

    private fun stopService() {
        try {
            appContext.stopService(Intent(appContext, ModelDownloadForegroundService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to stop model download FGS", e)
        }
    }

    companion object {
        private const val TAG = "ModelDownloadFgs"
    }
}
