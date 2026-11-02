package com.streamcam

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.streamcam.common.resilience.CpuUsage

/**
 * Runtime-only diagnostics (never persisted): process CPU, thermal status, battery temperature and
 * Wi-Fi link quality. Read once per METRICS tick so the 1 Hz line describes the device, not just the
 * pipeline.
 */
class DeviceMetrics(application: Application) {

    data class Snapshot(
        /** Percent of one core; > 100 on multi-core load (top-style). */
        val cpuPercent: Float,
        /** `PowerManager` thermal status 0..6; always 0 below API 29. */
        val thermalStatus: Int,
        val batteryTempC: Float?,
        /** Wi-Fi RSSI in dBm, or [NO_RSSI] when unavailable. */
        val wifiRssi: Int,
        /** Wi-Fi link speed in Mbps, 0 when unavailable. */
        val wifiLinkMbps: Int,
    )

    private val app: Application = application
    private val powerManager = application.getSystemService(PowerManager::class.java)
    private val wifiManager = application.getSystemService(WifiManager::class.java)

    private var lastCpuMs = 0L
    private var lastWallMs = 0L
    private var primed = false

    fun sample(): Snapshot {
        val cpuMs = Process.getElapsedCpuTime()
        val wallMs = SystemClock.elapsedRealtime()
        val cpuPercent = if (primed) CpuUsage.percent(lastCpuMs, lastWallMs, cpuMs, wallMs) else 0f
        lastCpuMs = cpuMs
        lastWallMs = wallMs
        primed = true

        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus ?: THERMAL_NONE
        } else {
            THERMAL_NONE
        }

        val battery = runCatching {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()?.let { intent ->
            val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tenths == Int.MIN_VALUE) null else tenths / 10f
        }

        val wifi = wifiInfo()
        return Snapshot(
            cpuPercent = cpuPercent,
            thermalStatus = thermal,
            batteryTempC = battery,
            wifiRssi = wifi?.rssi ?: NO_RSSI,
            wifiLinkMbps = wifi?.linkSpeed ?: 0,
        )
    }

    @Suppress("DEPRECATION")
    private fun wifiInfo(): android.net.wifi.WifiInfo? {
        // Deprecated on API 31+ and may be redacted without location access; diagnostics accept
        // whatever the platform reports rather than asking for more permissions.
        return runCatching { wifiManager?.connectionInfo }.getOrNull()
    }

    companion object {
        const val NO_RSSI = -127
        private const val THERMAL_NONE = 0
    }
}
