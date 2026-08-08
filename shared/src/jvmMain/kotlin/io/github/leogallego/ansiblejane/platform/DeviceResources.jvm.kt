package io.github.leogallego.ansiblejane.platform

import java.io.File
import java.lang.management.ManagementFactory

actual class DeviceResources {

    actual fun totalMemoryBytes(): Long {
        readMemTotalFromProc()?.let { return it }
        return totalMemoryFromOsBean()
    }

    actual fun freeDiskBytes(absolutePath: String): Long {
        return File(absolutePath).usableSpace
    }

    actual fun modelStorageDirectory(): String {
        val userHome = System.getProperty("user.home") ?: "."
        return File(userHome, ".ansiblejane/litert_models").absolutePath
    }

    actual fun hasAvx2Support(): Boolean {
        val arch = System.getProperty("os.arch")?.lowercase().orEmpty()
        if (arch != "amd64" && arch != "x86_64") {
            return true
        }
        return try {
            File("/proc/cpuinfo").useLines { lines ->
                lines.any { line ->
                    line.startsWith("flags") &&
                        line.split(Regex("\\s+")).any { it == "avx2" }
                }
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun readMemTotalFromProc(): Long? {
        return try {
            File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("MemTotal:") }
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                    ?.times(1024)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun totalMemoryFromOsBean(): Long {
        val osBean = ManagementFactory.getOperatingSystemMXBean()
        return when (osBean) {
            is com.sun.management.OperatingSystemMXBean -> osBean.totalMemorySize
            else -> Runtime.getRuntime().maxMemory()
        }
    }
}
