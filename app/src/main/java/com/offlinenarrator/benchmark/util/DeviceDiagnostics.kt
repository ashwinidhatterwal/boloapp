package com.offlinenarrator.benchmark.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process

data class DeviceSnapshot(
    val device: String,
    val androidVersion: String,
    val totalPssMb: Double,
    val nativePssMb: Double,
    val nativeHeapAllocatedMb: Double,
    val batteryTemperatureC: Double?,
    val thermalStatus: String,
    val cpuCores: Int,
)

object DeviceDiagnostics {
    fun capture(context: Context): DeviceSnapshot {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val process = activity.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
        val pssMb = (process?.totalPss ?: 0) / 1024.0
        val nativePssMb = (process?.nativePss ?: 0) / 1024.0
        val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val tempC = tempTenths?.takeIf { it != Int.MIN_VALUE }?.div(10.0)

        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "None"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                else -> "Unknown"
            }
        } else "Unavailable"

        return DeviceSnapshot(
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            totalPssMb = pssMb,
            nativePssMb = nativePssMb,
            nativeHeapAllocatedMb = nativeHeapMb,
            batteryTemperatureC = tempC,
            thermalStatus = thermal,
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        )
    }
}
