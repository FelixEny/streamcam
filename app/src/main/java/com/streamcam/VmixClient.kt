package com.streamcam

import android.os.SystemClock
import android.util.Log
import com.streamcam.common.vmix.VmixState
import com.streamcam.common.vmix.VmixXml
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only client for vMix's Web Controller API. */
object VmixClient {

    private const val TAG = "VmixClient"
    const val PORT = 8088
    private const val CONNECT_TIMEOUT_MS = 600
    private const val READ_TIMEOUT_MS = 900
    private const val ERROR_LOG_INTERVAL_MS = 10_000L

    private var lastErrorLogAtMs = 0L

    suspend fun fetch(host: String): VmixState? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("http://$host:$PORT/api/").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                logError("vMix HTTP $code from $host")
                return@withContext null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            VmixXml.parse(body).also {
                if (it == null) logError("vMix XML unparsable (${body.length} bytes)")
            }
        } catch (e: Exception) {
            logError("vMix fetch failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Poll runs at 2 Hz; a broken host must not flood logcat. */
    private fun logError(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastErrorLogAtMs < ERROR_LOG_INTERVAL_MS) return
        lastErrorLogAtMs = now
        Log.w(TAG, message)
    }
}
