package io.github.leogallego.ansiblejane.platform

/**
 * Desktop actual: intentional no-op.
 *
 * Workflow approval polling is Android-only (WorkManager + ApprovalPollingWorker in
 * `app/`). This class keeps the expect/actual API surface but does not schedule work.
 * See CLAUDE.md / service-contracts.md §3.
 */
actual class BackgroundWorker {
    actual fun schedulePolling(intervalMinutes: Long) {
        // no-op: approval polling is Android-only
    }

    actual fun cancelPolling() {
        // no-op: approval polling is Android-only
    }
}
