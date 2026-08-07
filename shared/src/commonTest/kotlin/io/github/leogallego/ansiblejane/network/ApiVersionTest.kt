package io.github.leogallego.ansiblejane.network

import io.github.leogallego.ansiblejane.model.ApiVersion

import kotlin.test.Test
import kotlin.test.assertEquals

class ApiVersionTest {

    @Test
    fun controllerV2HasExpectedPrefix() {
        assertEquals("/api/controller/v2/", ApiVersion.CONTROLLER_V2.prefix)
    }

    @Test
    fun v2HasExpectedPrefix() {
        assertEquals("/api/v2/", ApiVersion.V2.prefix)
    }

    @Test
    fun allPrefixesStartAndEndWithSlash() {
        ApiVersion.entries.forEach { version ->
            assertEquals('/', version.prefix.first(), "Prefix for $version must start with /")
            assertEquals('/', version.prefix.last(), "Prefix for $version must end with /")
        }
    }
}
