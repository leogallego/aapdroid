package io.github.leogallego.ansiblejane.assistant.local

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalModelContextTest {

    @Test
    fun clamp_boundsAndSteps() {
        assertEquals(4096, clampContextTokens(4096, 32768, 0))
        assertEquals(4096, clampContextTokens(4096, 32768, 4096))
        assertEquals(4096, clampContextTokens(4096, 32768, 5000))
        assertEquals(8192, clampContextTokens(4096, 32768, 8192))
        assertEquals(16384, clampContextTokens(4096, 32768, 16384))
        assertEquals(32768, clampContextTokens(4096, 32768, 32768))
        assertEquals(32768, clampContextTokens(4096, 32768, 99999))
    }

    @Test
    fun clamp_12bDefaultFloor() {
        assertEquals(8192, clampContextTokens(8192, 32768, 4096))
        assertEquals(8192, clampContextTokens(8192, 32768, 8192))
        assertEquals(9216, clampContextTokens(8192, 32768, 9216))
    }

    @Test
    fun resolve_zeroMeansCatalogDefault() {
        assertEquals(4096, resolveOnDeviceContextTokens("gemma-4-e4b-it", 0))
        assertEquals(8192, resolveOnDeviceContextTokens("gemma-4-12b-it", 0))
    }

    @Test
    fun resolve_selectionAndClamp() {
        assertEquals(16384, resolveOnDeviceContextTokens("gemma-4-e4b-it", 16384))
        assertEquals(32768, resolveOnDeviceContextTokens("gemma-4-e4b-it", 99999))
    }

    @Test
    fun resolve_unknownModelDefaultsTo4096() {
        assertEquals(4096, resolveOnDeviceContextTokens("unknown-model", 0))
        assertEquals(4096, resolveOnDeviceContextTokens("unknown-model", 8192))
    }
}
