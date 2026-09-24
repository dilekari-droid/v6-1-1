package tr.borsatakip.v5.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import tr.borsatakip.v5.data.SettingsStore
import java.util.concurrent.TimeUnit

object WorkScheduler {
    private const val NOTIFICATION_WORK = "opportunity_watch"
    private const val FORWARD_WORK = "forward_outcome_watch"

    fun reconcile(context: Context, notificationsEnabled: Boolean, refreshMinutes: Int) {
        val wm = WorkManager.getInstance(context)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        if (notificationsEnabled) {
            val minutes = refreshMinutes.coerceAtLeast(15)
            val req = PeriodicWorkRequestBuilder<OpportunityWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints).build()
            wm.enqueueUniquePeriodicWork(NOTIFICATION_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork(NOTIFICATION_WORK)
        }
        val forwardEnabled = SettingsStore(context).forwardOutcomeEnabled
        if (forwardEnabled) {
            val forwardReq = PeriodicWorkRequestBuilder<ForwardOutcomeWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints).build()
            wm.enqueueUniquePeriodicWork(FORWARD_WORK, ExistingPeriodicWorkPolicy.UPDATE, forwardReq)
        } else {
            wm.cancelUniqueWork(FORWARD_WORK)
        }
    }
}
