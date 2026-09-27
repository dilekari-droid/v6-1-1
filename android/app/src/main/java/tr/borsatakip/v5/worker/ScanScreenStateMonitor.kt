package tr.borsatakip.v5.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import tr.borsatakip.v5.data.ScanRuntimeLog

/** Dynamic receiver tied to an active foreground scan service. */
class ScanScreenStateMonitor(
    private val context: Context,
    private val source: String
) {
    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> ScanRuntimeLog.event(this@ScanScreenStateMonitor.context, "SCAN_SCREEN_LOCKED", "source=$source")
                    Intent.ACTION_SCREEN_ON -> ScanRuntimeLog.event(this@ScanScreenStateMonitor.context, "SCAN_SCREEN_UNLOCKED", "source=$source")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerLegacy(r, filter)
        }
        receiver = r
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy(r: BroadcastReceiver, filter: IntentFilter) {
        context.registerReceiver(r, filter)
    }

    fun stop() {
        val r = receiver ?: return
        runCatching { context.unregisterReceiver(r) }
        receiver = null
    }
}
