package tr.borsatakip.v5.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import tr.borsatakip.v5.data.SettingsStore
import java.util.concurrent.TimeUnit

/**
 * Otomatik tarama zamanlayıcısı.
 * 1/3/5/10 DK: kullanıcı-açık foreground service + 15 DK WorkManager watchdog.
 * 15 DK ve üzeri: normal WorkManager periyodik tarama.
 * Analiz timeframe'i ile tarama cadence'i birbirinden bağımsızdır.
 */
object AutoScanScheduler {
    private const val UNIQUE_WORK = "automatic_bist_scan"
    private const val IMMEDIATE_WORK = "automatic_bist_scan_now"
    private const val WATCHDOG_WORK = "automatic_bist_scan_watchdog"
    const val WORK_MANAGER_MINUTES = 15

    fun reconcile(context: Context, allowForegroundStart: Boolean) {
        val app = context.applicationContext
        val settings = SettingsStore(app)
        val wm = WorkManager.getInstance(app)
        if (!settings.autoScanEnabled) {
            wm.cancelUniqueWork(UNIQUE_WORK)
            wm.cancelUniqueWork(IMMEDIATE_WORK)
            wm.cancelUniqueWork(WATCHDOG_WORK)
            AutoScanForegroundService.stop(app)
            return
        }

        val cadence = settings.scanCadenceMinutes.coerceIn(1, 1440)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        if (cadence < WORK_MANAGER_MINUTES) {
            wm.cancelUniqueWork(UNIQUE_WORK)
            wm.cancelUniqueWork(IMMEDIATE_WORK)
            val watchdog = PeriodicWorkRequestBuilder<BackgroundWatchdogWorker>(WORK_MANAGER_MINUTES.toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(WATCHDOG_WORK, ExistingPeriodicWorkPolicy.UPDATE, watchdog)
            if (allowForegroundStart) AutoScanForegroundService.start(app)
            return
        }

        wm.cancelUniqueWork(WATCHDOG_WORK)
        AutoScanForegroundService.stop(app)
        val immediate = OneTimeWorkRequestBuilder<AutomaticScanWorker>()
            .setConstraints(constraints)
            .build()
        wm.enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, immediate)

        val request = PeriodicWorkRequestBuilder<AutomaticScanWorker>(cadence.toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
