package tr.borsatakip.v5.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import tr.borsatakip.v5.analysis.OpportunityEngine
import tr.borsatakip.v5.analysis.ViopScanner
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.BackendProvider
import tr.borsatakip.v5.data.ViopStrategyDecisionService
import tr.borsatakip.v5.data.ProviderReadinessService
import tr.borsatakip.v5.data.ProviderState
import tr.borsatakip.v5.data.AlertEventStore
import tr.borsatakip.v5.data.RealTimeIntegrityPolicy
import tr.borsatakip.v5.data.MarketCapabilityClient
import tr.borsatakip.v5.data.RealtimeScannerClient
import tr.borsatakip.v5.data.SignalHistoryStore
import tr.borsatakip.v5.data.favorites.FavoriteRepository
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.SignalValidity

class OpportunityWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val repo = FavoriteRepository.get(applicationContext)
        repo.migrateLegacyIfNeeded()
        val provider = ProviderRouter(applicationContext)

        return try {
            // Forward ölçümü ayrı worker ile bildirim tercihinden bağımsız yürütülür.
            val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("notifications", false)) return Result.success()

            // Primary path: server-side realtime engine continuously scans the licensed BIST universe.
            // This avoids reducing background alerts to only the user's first 20 favourites.
            val capability = MarketCapabilityClient(applicationContext).load().getOrNull()
            val realtimeRestEnabled = capability?.features?.realtimeScannerRest == true && capability.features.attestationReady
            val remoteResult = if (realtimeRestEnabled) RealtimeScannerClient(applicationContext).load(limit = 200) else null
            val analyzed = if (remoteResult?.isSuccess == true) {
                // A successful REMOTE snapshot with zero opportunities is a valid "no opportunity" result.
                remoteResult.getOrThrow().opportunities
            } else {
                // Backend capability kapalıysa 503 üretmeden doğrulanmış provider/favori uyumluluk yolu kullanılır.
                val symbols = repo.symbols("BIST").take(20)
                symbols.mapNotNull { symbol ->
                    val stock = provider.fetchOne(symbol) ?: return@mapNotNull null
                    if (!RealTimeIntegrityPolicy.validate(stock).accepted) return@mapNotNull null
                    OpportunityEngine.score(stock)
                }
            }
            val alertStore = AlertEventStore(applicationContext)
            // VİOP taraması BIST sonucundan bağımsızdır. BIST aday çıkarmasa bile production VİOP provider READY ise çalışır.
            try {
                val ready = ProviderReadinessService(applicationContext).localConfigState().state == ProviderState.PROVIDER_READY
                if (ready) {
                    val viopBackend = BackendProvider(applicationContext)
                    val viop = ViopScanner(viopBackend, provider, ViopStrategyDecisionService(applicationContext, viopBackend)).scan()
                    alertStore.evaluateViop(viop.opportunities)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // VİOP secondary-path failure must not invalidate a completed BIST scan.
            }
            if (analyzed.isEmpty()) return Result.success()
            alertStore.evaluate(analyzed)
            val hits = analyzed.filter {
                it.signalValidity == SignalValidity.VALID &&
                    it.dataMode == DataMode.REALTIME &&
                    it.finalSignalScore >= 80 &&
                    it.riskScore <= 60
            }

            if (hits.isNotEmpty()) {
                val prefsNotify = applicationContext.getSharedPreferences("opportunity_notification_cooldown", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val fresh = hits.filter { hit ->
                    val key = "${hit.symbol}:${hit.direction}"
                    val last = prefsNotify.getLong(key, 0L)
                    now - last >= NOTIFICATION_COOLDOWN_MS
                }
                if (fresh.isNotEmpty()) {
                    fresh.forEach { hit -> prefsNotify.edit().putLong("${hit.symbol}:${hit.direction}", now).apply() }
                    notify(applicationContext, fresh.take(3))
                }
            }
            Result.success()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NOTIFICATION_COOLDOWN_MS = 30 * 60 * 1000L

        fun notify(context: Context, hits: List<tr.borsatakip.v5.model.Opportunity>) {
            val title = "BORSA TAKİP fırsat uyarısı"
            val text = hits.joinToString(" • ") { hit ->
                val action = if (hit.direction.equals("SHORT", true)) "SAT ADAYI" else "AL ADAYI"
                "${hit.symbol} $action • ${hit.finalSignalScore}/100"
            }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val id = "opportunities"
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(id, "Fırsat Bildirimleri", NotificationManager.IMPORTANCE_DEFAULT))
            val first = hits.firstOrNull()
            val intent = Intent(context, tr.borsatakip.v5.ui.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (first != null) putExtra("symbol", first.symbol)
            }
            val pending = PendingIntent.getActivity(context, 5001, intent, PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            nm.notify(5001, NotificationCompat.Builder(context,id)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build())
        }
    }
}
