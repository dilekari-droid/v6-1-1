package tr.borsatakip.v5.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import tr.borsatakip.v5.data.SettingsStore
import kotlin.math.max

/**
 * Foreground servis üretici pil yönetimi/OS tarafından öldürülürse fail-safe olarak en az
 * WorkManager minimum periyodunda heartbeat'i denetler. Android arka plan kısıtlarını delmeye
 * çalışmaz; stale durumda yalnız bir güvenli tarama çalıştırır ve durumu kaydeder.
 */
class BackgroundWatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext)
        if (!settings.autoScanEnabled) return Result.success()

        val now = System.currentTimeMillis()
        val cadence = settings.scanCadenceMinutes.coerceIn(1, 1440)
        val freshLimitMs = max(5L, cadence.toLong() * 3L) * 60_000L
        val serviceHeartbeat = settings.autoScanServiceHeartbeatAt
        val runnerHeartbeat = settings.autoScanLastHeartbeatAt
        val freshest = max(serviceHeartbeat, runnerHeartbeat)
        if (freshest > 0L && now - freshest <= freshLimitMs) return Result.success()

        settings.autoScanLastStatus = "WATCHDOG_RECOVERY"
        settings.autoScanLastMessage = "Foreground heartbeat eski; WorkManager güvenlik taraması çalıştırılıyor."
        return when (AutomaticScanRunner.runOnce(applicationContext).outcome) {
            AutomaticScanRunner.Outcome.RETRYABLE_ERROR -> Result.retry()
            else -> Result.success()
        }
    }
}
