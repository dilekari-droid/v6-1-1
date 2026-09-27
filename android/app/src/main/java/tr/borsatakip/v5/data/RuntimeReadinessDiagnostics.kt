package tr.borsatakip.v5.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.worker.AutoScanScheduler
import java.net.URI

/** Local-only diagnostics. It never invents a provider result and never exposes the API key. */
object RuntimeReadinessDiagnostics {
    data class Snapshot(
        val appVersion: String,
        val androidVersion: String,
        val device: String,
        val backendOrigin: String,
        val apiKeyConfigured: Boolean,
        val notificationPermission: Boolean,
        val batteryOptimizationExempt: Boolean,
        val tradingViewHandlerAvailable: Boolean,
        val autoScanEnabled: Boolean,
        val autoScanCadenceMinutes: Int,
        val schedulerMode: String,
        val lastAutoScanStatus: String,
        val successfulRuns: Int,
        val failedRuns: Int,
        val consecutiveFailures: Int,
        val lastDurationMs: Long,
        val lastHeartbeatAt: Long,
        val bootRescheduleAt: Long
    )

    fun collect(context: Context): Snapshot {
        val app = context.applicationContext
        val s = SettingsStore(app)
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val notificationGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val tradingViewIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tradingview.com/chart/?symbol=BIST:KIMMR"))
        val handler = tradingViewIntent.resolveActivity(app.packageManager) != null
        val cadence = s.scanCadenceMinutes
        val scheduler = when {
            !s.autoScanEnabled -> "DISABLED"
            cadence < AutoScanScheduler.WORK_MANAGER_MINUTES -> "FOREGROUND_SPECIAL_USE"
            else -> "WORKMANAGER_PERIODIC"
        }
        return Snapshot(
            appVersion = BuildConfig.VERSION_NAME,
            androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            backendOrigin = sanitizedOrigin(s.baseUrl),
            apiKeyConfigured = s.apiKey.isNotBlank(),
            notificationPermission = notificationGranted,
            batteryOptimizationExempt = pm.isIgnoringBatteryOptimizations(app.packageName),
            tradingViewHandlerAvailable = handler,
            autoScanEnabled = s.autoScanEnabled,
            autoScanCadenceMinutes = cadence,
            schedulerMode = scheduler,
            lastAutoScanStatus = s.autoScanLastStatus,
            successfulRuns = s.autoScanSuccessfulRuns,
            failedRuns = s.autoScanFailedRuns,
            consecutiveFailures = s.autoScanConsecutiveFailures,
            lastDurationMs = s.autoScanLastDurationMs,
            lastHeartbeatAt = maxOf(s.autoScanLastHeartbeatAt, s.autoScanServiceHeartbeatAt),
            bootRescheduleAt = s.autoScanBootRescheduleAt
        )
    }

    private fun sanitizedOrigin(value: String): String = runCatching {
        val uri = URI(value.trim())
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank()) "YAPILANDIRILMAMIŞ"
        else "https://${uri.host.lowercase()}${if (uri.port > 0 && uri.port != 443) ":${uri.port}" else ""}"
    }.getOrDefault("YAPILANDIRILMAMIŞ")
}
