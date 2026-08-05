package io.github.leogallego.ansiblejane.platform

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

actual class BackgroundWorker {
    private val lock = ReentrantLock()
    private var executor: ScheduledExecutorService? = null
    private var scheduledTask: ScheduledFuture<*>? = null
    private var shutdownHook: Thread? = null

    actual fun schedulePolling(intervalMinutes: Long) {
        lock.withLock {
            cancelPollingInternal()

            val newExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "approval-polling").apply {
                    isDaemon = true
                }
            }

            // Intentional no-op: workflow approval polling is Android-only
            // (WorkManager + ApprovalPollingWorker in app/). Desktop keeps the
            // schedule/cancel API for expect/actual parity but does not poll.
            // See CLAUDE.md / service-contracts.md §3.
            val task = newExecutor.scheduleAtFixedRate(
                { /* no-op: approval polling is Android-only */ },
                intervalMinutes,
                intervalMinutes,
                TimeUnit.MINUTES
            )

            executor = newExecutor
            scheduledTask = task

            val hookExecutor = newExecutor
            val hook = Thread {
                hookExecutor.shutdownNow()
            }
            Runtime.getRuntime().addShutdownHook(hook)
            shutdownHook = hook
        }
    }

    actual fun cancelPolling() {
        lock.withLock {
            cancelPollingInternal()
        }
    }

    private fun cancelPollingInternal() {
        scheduledTask?.cancel(false)
        scheduledTask = null

        executor?.let {
            it.shutdown()
            try {
                if (!it.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                    it.shutdownNow()
                }
            } catch (e: InterruptedException) {
                it.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        executor = null

        shutdownHook?.let {
            try {
                Runtime.getRuntime().removeShutdownHook(it)
            } catch (_: IllegalStateException) {
                // JVM is already shutting down
            }
        }
        shutdownHook = null
    }
}
