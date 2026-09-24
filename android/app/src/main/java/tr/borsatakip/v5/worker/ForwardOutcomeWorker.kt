package tr.borsatakip.v5.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.SignalHistoryStore

class ForwardOutcomeWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = try {
        SignalHistoryStore(applicationContext).updateDueOutcomes(ProviderRouter(applicationContext), maxSignals = 40)
        Result.success()
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Exception) {
        Result.retry()
    }
}
