package tr.borsatakip.v5

import android.app.Application
import android.util.Log
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.ManualScanSessionRepository
import tr.borsatakip.v5.worker.WorkScheduler
import tr.borsatakip.v5.worker.AutoScanScheduler

class BorsaTakipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) installDebugCrashLogger()
        // Persisted manual scan state is recovered before any screen renders. If the previous
        // process died mid-scan it becomes INTERRUPTED, never a fake COMPLETED state.
        ManualScanSessionRepository.get(this)
        val s = SettingsStore(this)
        WorkScheduler.reconcile(this, s.notifications, s.refreshMinutes)
        if (s.autoScanEnabled) AutoScanScheduler.reconcile(this, allowForegroundStart = false)
    }

    private fun installDebugCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("BIST_CRASH", "[BIST_CRASH] thread=${thread.name} exception=${throwable::class.java.name} message=${throwable.message}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
