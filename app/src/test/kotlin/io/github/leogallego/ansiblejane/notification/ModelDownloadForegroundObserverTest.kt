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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ModelDownloadForegroundObserverTest {

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
    }

    @Test
    fun stopsService_onTerminalStates() = runTest {
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
        val stopped = shadow.nextStoppedService
        assertNotNull(stopped)
        assertEquals(
            ModelDownloadForegroundService::class.java.name,
            stopped!!.component!!.className,
        )

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
        assertNotNull(shadow.nextStoppedService)

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
        assertNotNull(shadow.nextStoppedService)
    }

    @Test
    fun cancelAction_callsRepositoryCancel() = runTest {
        val repo = FakeLocalModelRepository()
        val context = RuntimeEnvironment.getApplication()
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
        } finally {
            org.koin.core.context.stopKoin()
        }
    }
}
