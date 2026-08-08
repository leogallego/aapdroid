package io.github.leogallego.ansiblejane.presentation.settings

import aapremotecontrol.composeapp.generated.resources.Res
import aapremotecontrol.composeapp.generated.resources.agent_local_disk_insufficient
import aapremotecontrol.composeapp.generated.resources.agent_local_download_failed
import aapremotecontrol.composeapp.generated.resources.agent_local_download_network
import aapremotecontrol.composeapp.generated.resources.agent_local_download_timeout
import aapremotecontrol.composeapp.generated.resources.agent_local_hash_mismatch
import aapremotecontrol.composeapp.generated.resources.agent_local_import_failed
import io.github.leogallego.ansiblejane.assistant.local.DevicePerformance
import io.github.leogallego.ansiblejane.assistant.local.LOCAL_MODEL_CATALOG
import io.github.leogallego.ansiblejane.assistant.local.LocalModelDownloadErrorKind
import io.github.leogallego.ansiblejane.assistant.local.LocalModelDownloadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalModelUiMapperTest {

    @Test
    fun `download error kinds map to distinct string resources`() {
        assertEquals(
            Res.string.agent_local_disk_insufficient,
            LocalModelDownloadErrorKind.DISK.toUiMessage(),
        )
        assertEquals(
            Res.string.agent_local_hash_mismatch,
            LocalModelDownloadErrorKind.HASH.toUiMessage(),
        )
        assertEquals(
            Res.string.agent_local_download_timeout,
            LocalModelDownloadErrorKind.TIMEOUT.toUiMessage(),
        )
        assertEquals(
            Res.string.agent_local_download_network,
            LocalModelDownloadErrorKind.NETWORK.toUiMessage(),
        )
        assertEquals(
            Res.string.agent_local_import_failed,
            LocalModelDownloadErrorKind.IMPORT.toUiMessage(),
        )
        assertEquals(
            Res.string.agent_local_download_failed,
            LocalModelDownloadErrorKind.OTHER.toUiMessage(),
        )
    }

    @Test
    fun `repository download state maps to presentation ui state`() {
        val downloading = LocalModelDownloadState.Downloading(
            modelId = "gemma-4-e4b-it",
            bytesReceived = 50,
            totalBytes = 100,
            isImport = true,
        ).toUi()
        assertIs<LocalModelDownloadUiState.Downloading>(downloading)
        assertEquals(50, downloading.bytesReceived)
        assertTrue(downloading.isImport)

        val error = LocalModelDownloadState.Error(
            modelId = "gemma-4-e4b-it",
            kind = LocalModelDownloadErrorKind.HASH,
        ).toUi()
        assertIs<LocalModelDownloadUiState.Error>(error)
        assertEquals(Res.string.agent_local_hash_mismatch, error.message)
    }

    @Test
    fun `device performance maps to ui enum`() {
        assertEquals(DevicePerformanceUi.GOOD, DevicePerformance.GOOD.toUi())
        assertEquals(DevicePerformanceUi.OK, DevicePerformance.OK.toUi())
        assertEquals(DevicePerformanceUi.POOR, DevicePerformance.POOR.toUi())
    }

    @Test
    fun `formatLocalModelSizeGb uses one decimal without printf placeholders`() {
        assertEquals("3.4", formatLocalModelSizeGb(3_659_530_240L))
        assertEquals("6.1", formatLocalModelSizeGb(6_547_589_312L))
        assertEquals("0.0", formatLocalModelSizeGb(0L))
    }

    @Test
    fun `toUi carries optional existing import path`() {
        val model = LOCAL_MODEL_CATALOG.first()
        assertNull(model.toUi().existingImportPath)
        assertEquals(
            "/downloads/${model.fileName}",
            model.toUi(existingImportPath = "/downloads/${model.fileName}").existingImportPath,
        )
    }
}
