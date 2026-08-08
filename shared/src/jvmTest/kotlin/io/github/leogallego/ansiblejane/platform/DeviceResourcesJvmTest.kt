package io.github.leogallego.ansiblejane.platform

import kotlin.test.Test
import kotlin.test.assertTrue

class DeviceResourcesJvmTest {
    @Test
    fun modelStorageDirectory_isUnderAnsibleJane() {
        val dir = DeviceResources().modelStorageDirectory()
        assertTrue(dir.replace('\\', '/').contains(".ansiblejane/litert_models"))
    }

    @Test
    fun hasAvx2Support_returnsBooleanWithoutThrowing() {
        DeviceResources().hasAvx2Support() // must not throw on this machine
    }
}
