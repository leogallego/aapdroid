package io.github.leogallego.ansiblejane.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.leogallego.ansiblejane.MainActivity
import io.github.leogallego.ansiblejane.R
import io.github.leogallego.ansiblejane.assistant.local.ILocalModelRepository
import io.github.leogallego.ansiblejane.assistant.local.LocalModelDownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Ongoing foreground service for LiteRT **network** model downloads (#481).
 *
 * SAF/import transfers never start this service — see [ModelDownloadForegroundObserver].
 * Observes [ILocalModelRepository.downloadState] for progress and self-stops on
 * Idle / Error / Succeeded / cancel, or when the OS fires [onTimeout].
 */
class ModelDownloadForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "litert_model_download"
        const val NOTIFICATION_ID = 0x4A414E46 // "JANF" — distinct from approval summary
        const val ACTION_CANCEL = "io.github.leogallego.ansiblejane.action.CANCEL_MODEL_DOWNLOAD"
    }

    private val repository: ILocalModelRepository by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val started = try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(indeterminate = true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            true
        } catch (_: Exception) {
            false
        }
        if (!started) {
            stopSelf()
            return
        }
        startObserving()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            repository.cancelDownload()
            stopAndClear()
            return START_NOT_STICKY
        }
        if (!observing) {
            startObserving()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        repository.cancelDownload()
        stopAndClear()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        observing = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startObserving() {
        if (observing) return
        observing = true
        serviceScope.launch {
            repository.downloadState.collectLatest { state ->
                when (state) {
                    is LocalModelDownloadState.Downloading -> {
                        if (state.isImport) {
                            stopAndClear()
                        } else {
                            val percent = progressPercent(state.bytesReceived, state.totalBytes)
                            val title = displayName(state.modelId)
                            val text = if (percent != null) {
                                getString(R.string.model_download_notification_progress, percent)
                            } else {
                                getString(R.string.model_download_notification_text)
                            }
                            notifyProgress(
                                title = title,
                                text = text,
                                percent = percent,
                            )
                        }
                    }
                    else -> stopAndClear()
                }
            }
        }
    }

    private fun displayName(modelId: String): String =
        repository.catalog().find { it.id == modelId }?.displayName ?: modelId

    private fun progressPercent(bytesReceived: Long, totalBytes: Long): Int? {
        if (totalBytes <= 0L) return null
        return ((bytesReceived * 100L) / totalBytes).toInt().coerceIn(0, 100)
    }

    private fun notifyProgress(title: String, text: String, percent: Int?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                title = title,
                text = text,
                percent = percent,
                indeterminate = percent == null,
            ),
        )
    }

    private fun stopAndClear() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.model_download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.model_download_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(
        title: String = getString(R.string.app_name),
        text: String = getString(R.string.model_download_notification_text),
        percent: Int? = null,
        indeterminate: Boolean = true,
    ): Notification {
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val cancelIntent = Intent(this, ModelDownloadForegroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                getString(R.string.action_cancel),
                cancelPendingIntent,
            )

        if (indeterminate || percent == null) {
            builder.setProgress(100, 0, true)
        } else {
            builder.setProgress(100, percent, false)
        }
        return builder.build()
    }
}
