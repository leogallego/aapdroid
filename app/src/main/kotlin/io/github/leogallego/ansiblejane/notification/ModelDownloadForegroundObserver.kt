package io.github.leogallego.ansiblejane.notification

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
 *
 * Stop uses [ModelDownloadForegroundService.ACTION_STOP] via `startForegroundService`
 * (never bare `stopService`) so a fast Downloading→Error cannot kill the service
 * before `startForeground` (#494). Retries start when any activity reaches STARTED
 * if a network download is still desired after a failed/background start.
 */
class ModelDownloadForegroundObserver(
    private val appContext: Context,
    private val repository: ILocalModelRepository,
    private val scope: CoroutineScope,
) : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var networkDownloadDesired: Boolean = false

    fun start() {
        val app = appContext.applicationContext as Application
        app.registerActivityLifecycleCallbacks(this)
        scope.launch {
            repository.downloadState
                .map { state ->
                    state is LocalModelDownloadState.Downloading && !state.isImport
                }
                .distinctUntilChanged()
                .collect { networkDownloadActive ->
                    val wasDesired = networkDownloadDesired
                    networkDownloadDesired = networkDownloadActive
                    ModelDownloadForegroundService.desiredActive = networkDownloadActive
                    if (networkDownloadActive) {
                        requestStart()
                    } else if (wasDesired) {
                        // Only handshake-stop after an active download — never on
                        // the initial Idle emission from StateFlow (#494).
                        requestStop()
                    }
                }
        }
    }

    /** Retry FGS when returning to foreground if download is still active (#494). */
    override fun onActivityStarted(activity: Activity) {
        if (networkDownloadDesired) {
            ModelDownloadForegroundService.desiredActive = true
            requestStart()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun requestStart() {
        try {
            val intent = Intent(appContext, ModelDownloadForegroundService::class.java)
            ContextCompat.startForegroundService(appContext, intent)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start model download FGS", e)
        }
    }

    private fun requestStop() {
        try {
            // Handshake: deliver stop through startForegroundService so onCreate can
            // always call startForeground before tearing down (#494).
            val intent = Intent(appContext, ModelDownloadForegroundService::class.java).apply {
                action = ModelDownloadForegroundService.ACTION_STOP
            }
            ContextCompat.startForegroundService(appContext, intent)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to signal model download FGS stop; falling back", e)
            try {
                appContext.stopService(Intent(appContext, ModelDownloadForegroundService::class.java))
            } catch (stopError: Exception) {
                Log.w(TAG, "Unable to stop model download FGS", stopError)
            }
        }
    }

    companion object {
        private const val TAG = "ModelDownloadFgs"
    }
}
