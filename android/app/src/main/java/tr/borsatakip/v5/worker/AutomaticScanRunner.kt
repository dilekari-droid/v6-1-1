package tr.borsatakip.v5.worker

import tr.borsatakip.v5.data.ProviderReadinessService
import android.content.Context
import android.util.Log
import tr.borsatakip.v5.data.BackendPreflightClient
import tr.borsatakip.v5.data.IntervalMarketDataProvider
import tr.borsatakip.v5.data.LastSuccessfulScanStore
import tr.borsatakip.v5.data.ProviderException
import tr.borsatakip.v5.data.ProviderFallbackPolicy
import tr.borsatakip.v5.data.ProviderFailureCode
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.RealTimeIntegrityPolicy
import tr.borsatakip.v5.data.ScanTimeframe
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.SignalHistoryRecorder
import tr.borsatakip.v5.model.ScanMode
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.scan.BistScanMode
import tr.borsatakip.v5.scan.BistScanner
import tr.borsatakip.v5.scan.ScanStatus

/**
 * Otomatik taramanın tek gerçek çalışma yolu.
 * UI animasyonu üretmez; provider -> BIST universe -> seçilen timeframe OHLCV -> analiz -> sıralama
 * zincirini manuel taramayla aynı BistScanner üzerinden çalıştırır.
 */
object AutomaticScanRunner {
    enum class Outcome { COMPLETED, PARTIAL, NO_DATA, RETRYABLE_ERROR, CONFIG_ERROR, STOPPED }

    data class Result(
        val outcome: Outcome,
        val message: String,
        val processed: Int = 0,
        val total: Int = 0,
        val opportunities: Int = 0,
        val timeframeLabel: String = ""
    )

    suspend fun runOnce(context: Context): Result {
        val app = context.applicationContext
        val settings = SettingsStore(app)
        val timeframe = ScanTimeframe.fromStored(settings.analysisTimeframeMinutes)
        settings.scanCadenceMinutes = timeframe.storedMinutes.coerceIn(1, 1440)
        settings.scanMode = ScanMode.PERIODIC
        val startedAt = System.currentTimeMillis()
        settings.autoScanLastHeartbeatAt = startedAt
        if (settings.autoScanMonitoringStartedAt <= 0L) settings.autoScanMonitoringStartedAt = startedAt

        fun finish(result: Result): Result {
            val finishedAt = System.currentTimeMillis()
            settings.autoScanLastRunAt = finishedAt
            settings.autoScanLastHeartbeatAt = finishedAt
            settings.autoScanLastDurationMs = (finishedAt - startedAt).coerceAtLeast(0L)
            settings.autoScanLastStatus = result.outcome.name
            settings.autoScanLastMessage = result.message
            when (result.outcome) {
                Outcome.COMPLETED, Outcome.PARTIAL -> {
                    settings.autoScanSuccessfulRuns = settings.autoScanSuccessfulRuns + 1
                    settings.autoScanConsecutiveFailures = 0
                }
                Outcome.STOPPED -> Unit
                else -> {
                    settings.autoScanFailedRuns = settings.autoScanFailedRuns + 1
                    settings.autoScanConsecutiveFailures = settings.autoScanConsecutiveFailures + 1
                }
            }
            Log.i(TAG, "AUTO_SCAN outcome=${result.outcome} timeframe=${timeframe.apiInterval} processed=${result.processed}/${result.total} opportunities=${result.opportunities} message=${result.message}")
            return result
        }

        if (!settings.autoScanEnabled) {
            return finish(Result(Outcome.STOPPED, "Otomatik tarama kapalı.", timeframeLabel = timeframe.label))
        }
        val productionReady = ProviderReadinessService.isValidHttps(settings.baseUrl) && settings.apiKey.isNotBlank()
        val fallbackAllowed = ProviderFallbackPolicy.allowed(settings.experimentalProvidersEnabled, settings.yahooFallbackEnabled)
        if (!productionReady && !fallbackAllowed) {
            return finish(Result(
                Outcome.CONFIG_ERROR,
                "Production Backend hazır değil ve Yahoo fallback kullanıcı ayarlarında kapalı.",
                timeframeLabel = timeframe.label
            ))
        }

        return try {
            val preflight = if (productionReady) BackendPreflightClient(app).checkBist() else null
            if (preflight != null && !preflight.ok) {
                return finish(Result(
                    classifyPreflight(preflight.failureKind),
                    "${preflight.failureKind} • ${preflight.message}",
                    total = preflight.symbolCount,
                    timeframeLabel = timeframe.label
                ))
            }

            val sessionCloseMode = preflight?.bistAvailabilityMode == BackendPreflightClient.BistAvailabilityMode.SESSION_CLOSE
            val delayedAnalysisMode = preflight?.bistAvailabilityMode == BackendPreflightClient.BistAvailabilityMode.DELAYED_ANALYSIS
            val baseProvider = ProviderRouter(app)
            val provider = IntervalMarketDataProvider(
                baseProvider,
                timeframe.storedMinutes,
                sessionCloseMode = sessionCloseMode,
                delayedObservationMode = delayedAnalysisMode
            )

            if (productionReady) {
                val checked = requireNotNull(preflight)
                val sample = checked.sampleSymbol
                if (sample.isNullOrBlank()) {
                    return finish(Result(
                        Outcome.CONFIG_ERROR,
                        "BIST HİSSE LİSTESİ ALINAMADI: örnek sembol yok.",
                        total = checked.symbolCount,
                        timeframeLabel = timeframe.label
                    ))
                }

                // Production yolunda tüm evrene geçmeden seçilen timeframe OHLCV'sini doğrula.
                val probe = provider.fetchOne(sample)
                if (probe == null || probe.candles.size < RealTimeIntegrityPolicy.MIN_HISTORY_BARS) {
                    return finish(Result(
                        Outcome.NO_DATA,
                        "${timeframe.label} VERİ SERVİSİ HAZIR DEĞİL • $sample için yeterli gerçek OHLCV alınamadı.",
                        total = checked.symbolCount,
                        timeframeLabel = timeframe.label
                    ))
                }
            }

            // Backend yoksa Yahoo hiçbir zaman REALTIME sayılmaz. Seans dışındaki production yolunda
            // son kapanmış seans OHLCV'si ayrı bir gözlem modu olarak çalışır.
            val scanMode = when {
                productionReady && sessionCloseMode -> BistScanMode.SESSION_CLOSE_ANALYSIS
                productionReady && delayedAnalysisMode -> BistScanMode.DELAYED_ANALYSIS
                productionReady -> BistScanMode.REALTIME_ONLY
                else -> BistScanMode.DELAYED_ANALYSIS
            }
            val scanner = BistScanner(provider, SignalHistoryRecorder(app), scanMode)
            val final = scanner.scan { state ->
                settings.autoScanLastStatus = state.phase.name
                settings.autoScanLastMessage = "${timeframe.label} • ${state.processed}/${state.total}"
                settings.autoScanLastHeartbeatAt = System.currentTimeMillis()
            }

            val run = final.scanRun
            if (final.status == ScanStatus.COMPLETED && run?.status == ScanRunStatus.COMPLETE) {
                if (scanMode == BistScanMode.REALTIME_ONLY) LastSuccessfulScanStore(app).save(run, final.results)
                val completedMessage = when (scanMode) {
                    BistScanMode.REALTIME_ONLY -> "TARAMA TAMAMLANDI • ${timeframe.label} • ${final.results.size} fırsat"
                    BistScanMode.SESSION_CLOSE_ANALYSIS -> "KAPANIŞ TARAMASI TAMAMLANDI • ${timeframe.label} • ${final.results.size} teknik sonuç • canlı değil"
                    BistScanMode.DELAYED_ANALYSIS -> "TARAMA TAMAMLANDI • ${timeframe.label} • Yahoo YEDEK/GECİKMELİ • ${final.results.size} teknik gözlem"
                }
                finish(Result(
                    Outcome.COMPLETED,
                    completedMessage,
                    final.processed,
                    final.total,
                    final.results.size,
                    timeframe.label
                ))
            } else if (final.status == ScanStatus.COMPLETED && final.successful > 0) {
                val partialMessage = when (scanMode) {
                    BistScanMode.REALTIME_ONLY -> "KISMİ TARAMA • ${timeframe.label} • Başarılı ${final.successful}/${final.total} • Veri yok ${final.noData} • Hata ${final.errors}"
                    BistScanMode.SESSION_CLOSE_ANALYSIS -> "KISMİ KAPANIŞ TARAMASI • ${timeframe.label} • Teknik analiz ${final.successful}/${final.total} • Veri yok ${final.noData} • Hata ${final.errors}"
                    BistScanMode.DELAYED_ANALYSIS -> "KISMİ TARAMA • ${timeframe.label} • Yahoo YEDEK/GECİKMELİ • Teknik gözlem ${final.successful}/${final.total} • Veri yok ${final.noData} • Hata ${final.errors}"
                }
                finish(Result(
                    Outcome.PARTIAL,
                    partialMessage,
                    final.processed,
                    final.total,
                    final.results.size,
                    timeframe.label
                ))
            } else {
                finish(Result(
                    Outcome.NO_DATA,
                    final.errorMessage ?: "${timeframe.label} için geçerli tarama sonucu oluşmadı.",
                    final.processed,
                    final.total,
                    final.results.size,
                    timeframe.label
                ))
            }
        } catch (pe: ProviderException) {
            finish(Result(
                if (pe.code in RETRYABLE) Outcome.RETRYABLE_ERROR else if (pe.code in CONFIG_CODES) Outcome.CONFIG_ERROR else Outcome.NO_DATA,
                "${pe.code.name} • ${pe.message ?: "Provider hatası"}",
                timeframeLabel = timeframe.label
            ))
        } catch (t: Throwable) {
            finish(Result(Outcome.RETRYABLE_ERROR, t.message ?: t.javaClass.simpleName, timeframeLabel = timeframe.label))
        }
    }

    private fun classifyPreflight(kind: BackendPreflightClient.FailureKind): Outcome = when (kind) {
        BackendPreflightClient.FailureKind.BACKEND_NOT_CONFIGURED,
        BackendPreflightClient.FailureKind.API_KEY_MISSING,
        BackendPreflightClient.FailureKind.HTTPS_REQUIRED,
        BackendPreflightClient.FailureKind.INVALID_URL,
        BackendPreflightClient.FailureKind.AUTH_ERROR -> Outcome.CONFIG_ERROR
        BackendPreflightClient.FailureKind.NETWORK_TIMEOUT,
        BackendPreflightClient.FailureKind.DNS_ERROR,
        BackendPreflightClient.FailureKind.TLS_ERROR,
        BackendPreflightClient.FailureKind.RATE_LIMIT,
        BackendPreflightClient.FailureKind.SERVER_ERROR,
        BackendPreflightClient.FailureKind.HTTP_ERROR -> Outcome.RETRYABLE_ERROR
        else -> Outcome.NO_DATA
    }

    private val RETRYABLE = setOf(
        ProviderFailureCode.NETWORK_TIMEOUT,
        ProviderFailureCode.RATE_LIMIT,
        ProviderFailureCode.SERVER_ERROR,
        ProviderFailureCode.NETWORK_ERROR,
        ProviderFailureCode.DNS_ERROR,
        ProviderFailureCode.TLS_ERROR
    )
    private val CONFIG_CODES = setOf(
        ProviderFailureCode.BACKEND_URL_MISSING,
        ProviderFailureCode.API_KEY_MISSING,
        ProviderFailureCode.INVALID_HTTPS,
        ProviderFailureCode.AUTH_ERROR
    )
    private const val TAG = "AUTO_BIST_SCAN"
}
