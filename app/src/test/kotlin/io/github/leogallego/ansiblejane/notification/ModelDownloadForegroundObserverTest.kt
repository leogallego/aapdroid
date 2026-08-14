package io.github.leogallego.ansiblejane.notification

import android.content.Intent
import io.github.leogallego.ansiblejane.assistant.local.LocalModelDownloadErrorKind
import io.github.leogallego.ansiblejane.assistant.local.LocalModelDownloadState
import io.github.leogallego.ansiblejane.fakes.FakeLocalModelRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ModelDownloadForegroundObserverTest {

    @Before
    fun resetDesired() {
        ModelDownloadForegroundService.desiredActive = false
    }

    @Test
    fun startsService_forNetworkDownloading() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val repo = FakeLocalModelRepository()
        val context = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(context)

        ModelDownloadForegroundObserver(context, repo, scope).start()
        advanceUntilIdle()
        assertNull(shadow.nextStartedService)

        repo.emit(
            LocalModelDownloadState.Downloading(
                modelId = "gemma-4-e4b-it",
                bytesReceived = 1_000L,
                totalBytes = 10_000L,
                isImport = false,
            ),
        )
        advanceUntilIdle()

        val started = shadow.nextStartedService
        assertNotNull(started)
        assertEquals(
            ModelDownloadForegroundService::class.java.name,
            started!!.component!!.className,
        )
        assertTrue(ModelDownloadForegroundService.desiredActive)
    }

    @Test
    fun doesNotStartService_forImportDownloading() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val repo = FakeLocalModelRepository()
        val context = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(context)

        ModelDownloadForegroundObserver(context, repo, scope).start()
        repo.emit(
            LocalModelDownloadState.Downloading(
                modelId = "gemma-4-e4b-it",
                bytesReceived = 1_000L,
                totalBytes = 10_000L,
                isImport = true,
            ),
        )
        advanceUntilIdle()

        assertNull(shadow.nextStartedService)
        assertFalse(ModelDownloadForegroundService.desiredActive)
    }

    @Test
    fun stopUsesStartForegroundServiceHandshake_onTerminalStates() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val repo = FakeLocalModelRepository()
        val context = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(context)

        ModelDownloadForegroundObserver(context, repo, scope).start()
        repo.emit(
            LocalModelDownloadState.Downloading(
                modelId = "gemma-4-e4b-it",
                bytesReceived = 1_000L,
                totalBytes = 10_000L,
                isImport = false,
            ),
        )
        advanceUntilIdle()
        assertNotNull(shadow.nextStartedService)

        repo.emit(LocalModelDownloadState.Succeeded("gemma-4-e4b-it"))
        advanceUntilIdle()
        val stopIntent = shadow.nextStartedService
        assertNotNull(stopIntent)
        assertEquals(ModelDownloadForegroundService.ACTION_STOP, stopIntent!!.action)
        assertFalse(ModelDownloadForegroundService.desiredActive)
        assertNull(shadow.nextStoppedService)

        repo.emit(
            LocalModelDownloadState.Downloading(
                modelId = "gemma-4-e4b-it",
                bytesReceived = 0L,
                totalBytes = 10_000L,
                isImport = false,
            ),
        )
        advanceUntilIdle()
        assertNotNull(shadow.nextStartedService)

        repo.emit(
            LocalModelDownloadState.Error(
                "gemma-4-e4b-it",
                LocalModelDownloadErrorKind.NETWORK,
            ),
        )
        advanceUntilIdle()
        val errorStop = shadow.nextStartedService
        assertNotNull(errorStop)
        assertEquals(ModelDownloadForegroundService.ACTION_STOP, errorStop!!.action)

        repo.emit(
            LocalModelDownloadState.Downloading(
                modelId = "gemma-4-e4b-it",
                bytesReceived = 0L,
                totalBytes = 10_000L,
                isImport = false,
            ),
        )
        advanceUntilIdle()
        assertNotNull(shadow.nextStartedService)

        repo.emit(LocalModelDownloadState.Idle)
        advanceUntilIdle()
        val idleStop = shadow.nextStartedService
        assertNotNull(idleStop)
        assertEquals(ModelDownloadForegroundService.ACTION_STOP, idleStop!!.action)
    }

    @Test
    fun fastDownloadingToError_usesStopHandshakeNotBareStopService() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val repo = FakeLocalModelRepository()
        val context = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(context)

        ModelDownloadForegroundObserver(context, repo, scope).start()
        repo.emit(
            LocalModelDownloadState.Downloading(
                modelId = "gemma-4-e4b-it",
                bytesReceived = 1L,
                totalBytes = 10_000L,
                isImport = false,
            ),
        )
        repo.emit(
            LocalModelDownloadState.Error(
                "gemma-4-e4b-it",
                LocalModelDownloadErrorKind.NETWORK,
            ),
        )
        advanceUntilIdle()

        val first = shadow.nextStartedService
        assertNotNull(first)
        val second = shadow.nextStartedService
        assertNotNull(second)
        assertEquals(ModelDownloadForegroundService.ACTION_STOP, second!!.action)
        assertNull(shadow.nextStoppedService)
        assertFalse(ModelDownloadForegroundService.desiredActive)
    }

    @Test
    fun onActivityStarted_retriesStart_whenDownloadStillDesired() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val repo = FakeLocalModelRepository()
        val context = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(context)
        val observer = ModelDownloadForegroundObserver(context, repo, scope)
        observer.start()

        repo.emit(
            LocalModelDownloadState.Downloading(
                modelId = "gemma-4-e4b-it",
                bytesReceived = 1_000L,
                totalBytes = 10_000L,
                isImport = false,
            ),
        )
        advanceUntilIdle()
        assertNotNull(shadow.nextStartedService)

        // Simulate returning to foreground after a failed/background start attempt.
        observer.onActivityStarted(org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).get())
        advanceUntilIdle()
        val retry = shadow.nextStartedService
        assertNotNull(retry)
        assertNull(retry!!.action)
        assertTrue(ModelDownloadForegroundService.desiredActive)
    }

    @Test
    fun onActivityStarted_doesNotReviveFgs_afterCancelClearsDesiredActive() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val repo = FakeLocalModelRepository()
        val context = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(context)
        val observer = ModelDownloadForegroundObserver(context, repo, scope)
        observer.start()

        repo.emit(
            LocalModelDownloadState.Downloading(
                modelId = "gemma-4-e4b-it",
                bytesReceived = 1_000L,
                totalBytes = 10_000L,
                isImport = false,
            ),
        )
        advanceUntilIdle()
        assertNotNull(shadow.nextStartedService)

        // Notification Cancel clears desiredActive while downloadState may still be
        // Downloading briefly — retry must not force the latch back on.
        ModelDownloadForegroundService.desiredActive = false
        observer.onActivityStarted(
            org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).get(),
        )
        advanceUntilIdle()

        assertNull(shadow.nextStartedService)
        assertFalse(ModelDownloadForegroundService.desiredActive)
    }

    @Test
    fun progressThrottle_publishesFirstAndPercentOrIntervalChanges() {
        assertTrue(
            ModelDownloadForegroundService.shouldPublishProgress(
                lastNotifyAtElapsedMs = 0L,
                lastNotifiedPercent = null,
                percent = 1,
                nowElapsedMs = 100L,
            ),
        )
        assertFalse(
            ModelDownloadForegroundService.shouldPublishProgress(
                lastNotifyAtElapsedMs = 1_000L,
                lastNotifiedPercent = 10,
                percent = 10,
                nowElapsedMs = 1_100L,
            ),
        )
        assertTrue(
            ModelDownloadForegroundService.shouldPublishProgress(
                lastNotifyAtElapsedMs = 1_000L,
                lastNotifiedPercent = 10,
                percent = 11,
                nowElapsedMs = 1_100L,
            ),
        )
        assertTrue(
            ModelDownloadForegroundService.shouldPublishProgress(
                lastNotifyAtElapsedMs = 1_000L,
                lastNotifiedPercent = 10,
                percent = 10,
                nowElapsedMs = 1_000L + ModelDownloadForegroundService.NOTIFY_MIN_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun cancelAction_callsRepositoryCancel() = runTest {
        val repo = FakeLocalModelRepository()
        val context = RuntimeEnvironment.getApplication()
        ModelDownloadForegroundService.desiredActive = true
        repo.emit(
            LocalModelDownloadState.Downloading(
                modelId = "gemma-4-e4b-it",
                bytesReceived = 500L,
                totalBytes = 1_000L,
                isImport = false,
            ),
        )
        org.koin.core.context.startKoin {
            modules(
                org.koin.dsl.module {
                    single<io.github.leogallego.ansiblejane.assistant.local.ILocalModelRepository> {
                        repo
                    }
                },
            )
        }
        try {
            val service = org.robolectric.Robolectric.buildService(
                ModelDownloadForegroundService::class.java,
                Intent(context, ModelDownloadForegroundService::class.java),
            ).create().get()
            service.onStartCommand(
                Intent(context, ModelDownloadForegroundService::class.java).apply {
                    action = ModelDownloadForegroundService.ACTION_CANCEL
                },
                0,
                1,
            )
            assertEquals(1, repo.cancelCount)
            assertEquals(LocalModelDownloadState.Idle, repo.downloadState.value)
            assertFalse(ModelDownloadForegroundService.desiredActive)
        } finally {
            org.koin.core.context.stopKoin()
        }
    }

    @Test
    fun actionStop_clearsDesiredAndStopsWithoutCancel() = runTest {
        val repo = FakeLocalModelRepository()
        val context = RuntimeEnvironment.getApplication()
        ModelDownloadForegroundService.desiredActive = true
        repo.emit(
            LocalModelDownloadState.Downloading(
                modelId = "gemma-4-e4b-it",
                bytesReceived = 500L,
                totalBytes = 1_000L,
                isImport = false,
            ),
        )
        org.koin.core.context.startKoin {
            modules(
                org.koin.dsl.module {
                    single<io.github.leogallego.ansiblejane.assistant.local.ILocalModelRepository> {
                        repo
                    }
                },
            )
        }
        try {
            val service = org.robolectric.Robolectric.buildService(
                ModelDownloadForegroundService::class.java,
                Intent(context, ModelDownloadForegroundService::class.java),
            ).create().get()
            service.onStartCommand(
                Intent(context, ModelDownloadForegroundService::class.java).apply {
                    action = ModelDownloadForegroundService.ACTION_STOP
                },
                0,
                1,
            )
            assertEquals(0, repo.cancelCount)
            assertFalse(ModelDownloadForegroundService.desiredActive)
        } finally {
            org.koin.core.context.stopKoin()
        }
    }
}
