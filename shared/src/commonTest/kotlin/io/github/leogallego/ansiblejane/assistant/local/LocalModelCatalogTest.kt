package io.github.leogallego.ansiblejane.assistant.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalModelCatalogTest {
    @Test
    fun catalog_hasE4bAndLarge_only() {
        assertEquals(2, LOCAL_MODEL_CATALOG.size)
        assertTrue(LOCAL_MODEL_CATALOG.any { it.onDeviceTier == OnDeviceTier.E4B })
        assertTrue(LOCAL_MODEL_CATALOG.any { it.onDeviceTier == OnDeviceTier.LARGE })
    }

    @Test
    fun estimateGpuMemoryMb_includesBaselineAndExtraKv() {
        val model = LOCAL_MODEL_CATALOG.first { it.onDeviceTier == OnDeviceTier.E4B }
        val atDefault = estimateGpuMemoryMb(model, model.defaultContextTokens)
        val higher = estimateGpuMemoryMb(model, model.defaultContextTokens + 1024)
        assertTrue(higher > atDefault)
    }

    @Test
    fun calculateDevicePerformance_thresholds() {
        // 2.5x → GOOD, 1.85x → OK, below → POOR
        assertEquals(DevicePerformance.GOOD, calculateDevicePerformance(2_500L * 1024 * 1024, 1000))
        assertEquals(DevicePerformance.OK, calculateDevicePerformance(1_900L * 1024 * 1024, 1000))
        assertEquals(DevicePerformance.POOR, calculateDevicePerformance(1_000L * 1024 * 1024, 1000))
    }

    @Test
    fun catalog_entries_havePinnedUrlAndSha256() {
        val pinnedCommitPattern = Regex("/resolve/[0-9a-f]{40}/")
        LOCAL_MODEL_CATALOG.forEach { m ->
            assertTrue(m.downloadUrl.contains("/resolve/"), m.id)
            assertTrue(!m.downloadUrl.contains("/resolve/main"), "${m.id} must not use resolve/main")
            assertTrue(pinnedCommitPattern.containsMatchIn(m.downloadUrl), "${m.id} must pin to commit SHA")
            assertTrue(m.sha256.length == 64, m.id)
        }
    }
}
