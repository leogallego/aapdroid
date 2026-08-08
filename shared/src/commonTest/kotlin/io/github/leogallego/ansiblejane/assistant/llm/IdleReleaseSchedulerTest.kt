package io.github.leogallego.ansiblejane.assistant.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Virtual-time coverage for 5-minute idle release (#264 PR1 AC).
 * No LiteRT Engine required — only the scheduler's delay + cancel semantics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleReleaseSchedulerTest {

    @Test
    fun `fires onRelease after idle delay`() = runTest {
        var releases = 0
        val scheduler = IdleReleaseScheduler(
            scope = this,
            idleMs = 5L * 60 * 1000,
            onRelease = { releases++ },
        )

        scheduler.schedule()
        advanceTimeBy(5L * 60 * 1000 - 1)
        runCurrent()
        assertEquals(0, releases)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, releases)
    }

    @Test
    fun `reschedule resets the idle timer`() = runTest {
        var releases = 0
        val scheduler = IdleReleaseScheduler(
            scope = this,
            idleMs = 5L * 60 * 1000,
            onRelease = { releases++ },
        )

        scheduler.schedule()
        advanceTimeBy(4L * 60 * 1000)
        runCurrent()
        scheduler.schedule() // inference activity resets timer

        advanceTimeBy(4L * 60 * 1000)
        runCurrent()
        assertEquals(0, releases)

        advanceTimeBy(1L * 60 * 1000)
        runCurrent()
        assertEquals(1, releases)
    }

    @Test
    fun `cancel prevents release`() = runTest {
        var releases = 0
        val scheduler = IdleReleaseScheduler(
            scope = this,
            idleMs = 5L * 60 * 1000,
            onRelease = { releases++ },
        )

        scheduler.schedule()
        scheduler.cancel()
        advanceTimeBy(5L * 60 * 1000 + 1)
        runCurrent()
        assertEquals(0, releases)
    }

    @Test
    fun `default idle is five minutes`() {
        assertEquals(5L * 60 * 1000, IdleReleaseScheduler.DEFAULT_IDLE_MS)
    }
}
