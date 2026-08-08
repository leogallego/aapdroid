package io.github.leogallego.ansiblejane.platform

expect class DeviceResources {
    fun totalMemoryBytes(): Long
    fun freeDiskBytes(absolutePath: String): Long
    fun modelStorageDirectory(): String
    /** Android always true; desktop x86_64 Linux checks /proc/cpuinfo; else true. */
    fun hasAvx2Support(): Boolean
}
