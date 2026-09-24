package tr.borsatakip.v5.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tr.borsatakip.v5.data.SettingsStore

/**
 * Re-establishes user-enabled automatic scanning after reboot/package replacement.
 * Android restricts background foreground-service starts on recent versions, so sub-15 minute
 * scanning is marked as waiting for the next foreground app start instead of pretending it resumed.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in SUPPORTED_ACTIONS) return
        val settings = SettingsStore(context)
        if (!settings.autoScanEnabled) return
        settings.autoScanBootRescheduleAt = System.currentTimeMillis()
        AutoScanScheduler.reconcile(context, allowForegroundStart = false)
        if (settings.scanCadenceMinutes < AutoScanScheduler.WORK_MANAGER_MINUTES) {
            settings.autoScanLastStatus = "BOOT_WATCHDOG_ACTIVE"
            settings.autoScanLastMessage = "Yeniden başlatma sonrası Android foreground servis kısıtı uygulanıyor; 15 DK WorkManager watchdog etkin, uygulama açıldığında kısa periyot servisi yeniden devreye girer."
        }
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
