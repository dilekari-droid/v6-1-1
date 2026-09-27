package tr.borsatakip.v5.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** WorkManager yolu: Android'in 15 dakika ve üzeri periyodik iş sözleşmesi için. */
class AutomaticScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (AutomaticScanRunner.runOnce(applicationContext).outcome) {
        AutomaticScanRunner.Outcome.COMPLETED,
        AutomaticScanRunner.Outcome.PARTIAL,
        AutomaticScanRunner.Outcome.NO_DATA,
        AutomaticScanRunner.Outcome.CONFIG_ERROR,
        AutomaticScanRunner.Outcome.STOPPED -> Result.success()
        AutomaticScanRunner.Outcome.RETRYABLE_ERROR -> Result.retry()
    }
}
