package io.github.leogallego.ansiblejane

import android.app.Application
import android.util.Log
import io.github.leogallego.ansiblejane.assistant.di.sharedAssistantModule
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.leogallego.ansiblejane.data.IUserPreferencesRepository
import io.github.leogallego.ansiblejane.model.PollInterval
import io.github.leogallego.ansiblejane.data.dataModule
import io.github.leogallego.ansiblejane.assistant.local.ILocalModelRepository
import io.github.leogallego.ansiblejane.notification.ApprovalNotificationManager
import io.github.leogallego.ansiblejane.notification.ApprovalPollingWorker
import io.github.leogallego.ansiblejane.notification.ModelDownloadForegroundObserver
import io.github.leogallego.ansiblejane.platform.DataStoreFactory
import io.github.leogallego.ansiblejane.platform.SecureKeyStorage
import io.github.leogallego.ansiblejane.platform.TinkMigration
import io.github.leogallego.ansiblejane.presentation.presentationModule
import io.github.leogallego.ansiblejane.ui.components.DateFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import kotlinx.datetime.TimeZone
import java.util.concurrent.TimeUnit

class AnsibleJaneApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        io.github.leogallego.ansiblejane.assistant.engine.DebugLog.enabled =
            BuildConfig.DEBUG || BuildConfig.VERSION_NAME.contains("-")

        startKoin {
            androidLogger()
            androidContext(this@AnsibleJaneApp)
            modules(dataModule, presentationModule, sharedAssistantModule)
        }

        appScope.launch(Dispatchers.IO) {
            val migration = TinkMigration(this@AnsibleJaneApp)
            val dataStoreFactory: DataStoreFactory by inject()
            val secureKeyStorage: SecureKeyStorage by inject()
            when (val result = migration.migrateIfNeeded(dataStoreFactory, secureKeyStorage)) {
                is TinkMigration.MigrationResult.Success ->
                    Log.i("AnsibleJaneApp", "Tink migration: ${result.count} credentials migrated")
                is TinkMigration.MigrationResult.Partial ->
                    Log.w("AnsibleJaneApp", "Tink migration partial: ${result.migrated} ok, ${result.failed} failed")
                is TinkMigration.MigrationResult.Failed ->
                    Log.e("AnsibleJaneApp", "Tink migration failed: ${result.reason}")
                is TinkMigration.MigrationResult.NotNeeded -> {}
            }
        }

        ApprovalNotificationManager.createChannel(this)

        val localModelRepository: ILocalModelRepository by inject()
        ModelDownloadForegroundObserver(
            appContext = this,
            repository = localModelRepository,
            scope = appScope,
        ).start()

        val userPreferences: IUserPreferencesRepository by inject()

        appScope.launch {
            var firstEmission = true
            userPreferences.approvalPollInterval
                .distinctUntilChanged()
                .collect { interval ->
                    val policy = if (firstEmission)
                        ExistingPeriodicWorkPolicy.KEEP
                    else
                        ExistingPeriodicWorkPolicy.REPLACE
                    WorkManager.getInstance(this@AnsibleJaneApp).enqueueUniquePeriodicWork(
                        ApprovalPollingWorker.WORK_NAME,
                        policy,
                        PeriodicWorkRequestBuilder<ApprovalPollingWorker>(
                            interval.minutes.toLong(), TimeUnit.MINUTES
                        ).build()
                    )
                    firstEmission = false
                }
        }

        DateFormatter.systemIs24Hour = android.text.format.DateFormat.is24HourFormat(this)

        appScope.launch {
            val savedZone = userPreferences.timezoneId.first()
            DateFormatter.zoneOverride = savedZone?.let {
                try { TimeZone.of(it) } catch (_: Exception) { null }
            }
            DateFormatter.timeFormat = userPreferences.timeFormat.first()
        }
    }
}
