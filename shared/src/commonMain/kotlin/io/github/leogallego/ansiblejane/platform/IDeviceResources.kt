package io.github.leogallego.ansiblejane.platform

/**
 * Testable surface for device RAM/disk/AVX and on-device model storage paths.
 * [DeviceResources] is the production expect/actual; tests use fakes of this interface.
 */
interface IDeviceResources {
    fun totalMemoryBytes(): Long
    fun freeDiskBytes(absolutePath: String): Long
    fun modelStorageDirectory(): String
    fun hasAvx2Support(): Boolean
}

fun DeviceResources.asIDeviceResources(): IDeviceResources =
    object : IDeviceResources {
        override fun totalMemoryBytes(): Long = this@asIDeviceResources.totalMemoryBytes()
        override fun freeDiskBytes(absolutePath: String): Long =
            this@asIDeviceResources.freeDiskBytes(absolutePath)
        override fun modelStorageDirectory(): String =
            this@asIDeviceResources.modelStorageDirectory()
        override fun hasAvx2Support(): Boolean = this@asIDeviceResources.hasAvx2Support()
    }
