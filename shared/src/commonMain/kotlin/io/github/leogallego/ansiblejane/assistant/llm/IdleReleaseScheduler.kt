package io.github.leogallego.ansiblejane.assistant.llm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Schedules a one-shot idle callback after [idleMs] of inactivity.
 * Used by [LocalLlmProvider] to release LiteRT engine resources after 5 minutes.
 * Cancelled and rescheduled on each inference; cancelled on [close]-equivalent paths.
 */
internal class IdleReleaseScheduler(
    private val scope: CoroutineScope,
    private val idleMs: Long = DEFAULT_IDLE_MS,
    private val onRelease: () -> Unit,
) {
    private var job: Job? = null

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun schedule() {
        cancel()
        job = scope.launch {
            delay(idleMs)
            onRelease()
        }
    }

    companion object {
        const val DEFAULT_IDLE_MS: Long = 5L * 60 * 1000
    }
}
