package io.github.leogallego.ansiblejane.platform

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import java.io.File

actual class DeviceResources(private val context: Context) {

    actual fun totalMemoryBytes(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem
    }

    actual fun freeDiskBytes(absolutePath: String): Long {
        return StatFs(absolutePath).availableBytes
    }

    actual fun modelStorageDirectory(): String {
        return File(context.filesDir, "litert_models").absolutePath
    }

    actual fun hasAvx2Support(): Boolean = true
}
