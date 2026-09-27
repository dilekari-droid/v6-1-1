package tr.borsatakip.v5.scan

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tr.borsatakip.v5.analysis.OpportunityEngine
import tr.borsatakip.v5.analysis.OpportunityRankingPolicy
import tr.borsatakip.v5.analysis.OpportunityPublicationPolicy
import tr.borsatakip.v5.analysis.MarketRegimeEngine
import tr.borsatakip.v5.analysis.MtfConsensusEngine
import tr.borsatakip.v5.analysis.OhlcvResampler
import tr.borsatakip.v5.analysis.v531.V531ContextEngine
import tr.borsatakip.v5.analysis.V540FinalDecisionFlow
import tr.borsatakip.v5.analysis.v531.V531Direction
import tr.borsatakip.v5.analysis.v540.V540FreshnessPolicy
import tr.borsatakip.v5.data.MarketDataProvider
import tr.borsatakip.v5.data.MtfHistoryCache
import tr.borsatakip.v5.data.ProviderException
import tr.borsatakip.v5.data.ProviderScanDiagnostics
import tr.borsatakip.v5.data.ScanDiagnosticsSource
import tr.borsatakip.v5.data.ScanProgressMetadataSource
import tr.borsatakip.v5.data.ScanTimeframe
import tr.borsatakip.v5.data.RealTimeIntegrityPolicy
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ScanRun
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.model.Stock
import java.util.UUID

enum class ScanStatus { IDLE, RUNNING, COMPLETED, ERROR, CANCELLED }

enum class ScanPhase {
    IDLE, READY, STARTING, LOADING_SYMBOLS, LOADING_DATA, SCANNING,
    CALCULATING, RANKING, COMPLETED, ERROR, NO_DATA, STOPPED
}

enum class BistScanMode {
    REALTIME_ONLY,
    SESSION_CLOSE_ANALYSIS,
    DELAYED_ANALYSIS;

    val observationOnly: Boolean get() = this != REALTIME_ONLY
}

data class ScanState(
    val status: ScanStatus = ScanStatus.IDLE,
    val phase: ScanPhase = ScanPhase.IDLE,
    val scanMode: BistScanMode = BistScanMode.REALTIME_ONLY,
    /** İşleme ilerlemesi: processed / total. Başarı yüzdesi değildir. */
    val progress: Int = 0,
    val processed: Int = 0,
    val total: Int = 0,
    /** Geçerli bir Opportunity sonucu üreten sembol sayısı. */
    val successful: Int = 0,
    /** Veri çekilemeyen veya analiz sonucu üretilemeyen sembol sayısı. */
    val skipped: Int = 0,
    val noData: Int = 0,
    val errors: Int = 0,
    /** Sonuç üretse bile production/realtime bütünlük kapısından geçmeyen kayıt sayısı. */
    val integrityRejected: Int = 0,
    val results: List<Opportunity> = emptyList(),
    /** Tarama turunda sağlayıcıdan alınarak analiz kuyruğuna giren semboller. */
    val scannedSymbols: List<String> = emptyList(),
    /** Veri alınmış olsa da analiz sonucu üretilemeyen semboller. */
    val failedSymbols: List<String> = emptyList(),
    val errorMessage: String? = null,
    val scanRun: ScanRun? = null,
    /** Merkezi HistoryRecorder tarafından yeni eklenen kayıt sayısı. */
    val historyPersisted: Int = 0,
    /** Tarama sonucu başarılı olsa bile history yazımı ayrıca hata verebilir. */
    val historyError: String? = null,
    val marketRegime: MarketRegimeEngine.Result? = null,
    val timeframeLabel: String = "",
    /** Gerçek provider akışında son/aktif işlenen sembol; UI bunu uydurmaz. */
    val currentSymbol: String? = null
)

/**
 * HistoryRecorder zorunlu bağımlılıktır. Böylece BistScanner hangi ekrandan veya servisten
 * çağrılırsa çağrılsın başarılı COMPLETE/PARTIAL sonuçların history akışı atlanamaz.
 */
class BistScanner(
    private val provider: MarketDataProvider,
    private val historyRecorder: HistoryRecorder,
    private val mode: BistScanMode = BistScanMode.REALTIME_ONLY
) {

    suspend fun scan(onState: (ScanState) -> Unit): ScanState {
        val scanStartedAt = System.currentTimeMillis()
        val scanRunId = UUID.randomUUID().toString()
        var total = 0
        var processed = 0
        var fetched = 0

        fun run(status: ScanRunStatus, completedAt: Long? = null, count: Int = fetched, errors: Int = 0) = ScanRun(
            scanRunId, scanStartedAt, completedAt, provider.id, status, count, errors
        )

        Log.i(TAG, "[BIST_SCAN] START id=$scanRunId provider=${provider.id}")
        onState(ScanState(
            status = ScanStatus.IDLE,
            phase = ScanPhase.READY,
            scanMode = mode,
            scanRun = run(ScanRunStatus.STARTED),
            timeframeLabel = timeframeLabel()
        ))
        onState(ScanState(
            status = ScanStatus.RUNNING,
            phase = ScanPhase.STARTING,
            scanMode = mode,
            scanRun = run(ScanRunStatus.STARTED),
            timeframeLabel = timeframeLabel()
        ))

        return try {
            onState(ScanState(
                status = ScanStatus.RUNNING,
                phase = ScanPhase.LOADING_SYMBOLS,
                scanMode = mode,
                scanRun = run(ScanRunStatus.STARTED),
                timeframeLabel = timeframeLabel()
            ))
            val universe = provider.listSymbols().map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
            if (universe.isNotEmpty()) {
                total = universe.size
                onState(ScanState(
                    status = ScanStatus.RUNNING,
                    phase = ScanPhase.LOADING_DATA,
                    scanMode = mode,
                    total = total,
                    processed = 0,
                    progress = 0,
                    scanRun = run(ScanRunStatus.STARTED),
                    timeframeLabel = timeframeLabel()
                ))
            }

            val stocks = provider.scan { done, providerTotal ->
                processed = done.coerceAtLeast(0)
                if (total <= 0) total = providerTotal.coerceAtLeast(0)
                val effectiveTotal = if (total > 0) total else providerTotal.coerceAtLeast(0)
                onState(
                    ScanState(
                        status = ScanStatus.RUNNING,
                        phase = ScanPhase.SCANNING,
                        scanMode = mode,
                        progress = safeProgress(processed, effectiveTotal),
                        processed = processed,
                        total = effectiveTotal,
                        successful = 0,
                        skipped = 0,
                        scanRun = run(ScanRunStatus.STARTED),
                        timeframeLabel = timeframeLabel(),
                        currentSymbol = (provider as? ScanProgressMetadataSource)?.currentScanSymbol()
                    )
                )
            }

            fetched = stocks.size
            if (total == 0) {
                val completed = System.currentTimeMillis()
                return ScanState(
                    status = ScanStatus.ERROR,
                    phase = ScanPhase.ERROR,
                    scanMode = mode,
                    errorMessage = "BIST HİSSE LİSTESİ ALINAMADI.",
                    scanRun = run(ScanRunStatus.FAILED, completed, errors = 1),
                    timeframeLabel = timeframeLabel()
                ).also(onState)
            }
            if (stocks.isEmpty()) {
                val completed = System.currentTimeMillis()
                val providerDiagnostics = (provider as? ScanDiagnosticsSource)?.scanDiagnostics() ?: ProviderScanDiagnostics()
                return ScanState(
                    status = ScanStatus.ERROR,
                    phase = ScanPhase.NO_DATA,
                    scanMode = mode,
                    progress = safeProgress(processed, total),
                    processed = processed.coerceAtMost(total),
                    total = total,
                    successful = 0,
                    skipped = total,
                    noData = providerDiagnostics.noDataCount.coerceAtMost(total),
                    errors = providerDiagnostics.errorCount.coerceAtMost(total),
                    errorMessage = "SEÇİLEN PERİYOT İÇİN VERİ KULLANILAMIYOR.",
                    scanRun = run(ScanRunStatus.FAILED, completed, errors = total),
                    timeframeLabel = timeframeLabel()
                ).also(onState)
            }

            onState(ScanState(
                status = ScanStatus.RUNNING,
                phase = ScanPhase.CALCULATING,
                scanMode = mode,
                progress = safeProgress(processed, total),
                processed = processed,
                total = total,
                scanRun = run(ScanRunStatus.STARTED),
                timeframeLabel = timeframeLabel()
            ))
            val regime = loadMarketRegime()
            val analyzed = analyzeSafely(stocks)
            val regimeEnhanced = if (mode == BistScanMode.REALTIME_ONLY) {
                applyMtfConsensus(analyzed.results, regime)
            } else {
                analyzed.results.map { opportunity ->
                    val engineDirection = when (opportunity.direction.uppercase()) {
                        "LONG" -> V531Direction.LONG
                        "SHORT" -> V531Direction.SHORT
                        else -> V531Direction.WATCH
                    }
                    val directionLabel = when (engineDirection) {
                        V531Direction.LONG -> "LONG"
                        V531Direction.SHORT -> "SHORT"
                        V531Direction.WATCH -> "NEUTRAL"
                    }
                    val regimeAdjustment = regime?.let { MarketRegimeEngine.rankingAdjustment(directionLabel, it) } ?: 0
                    val freshnessScore = V540FreshnessPolicy.score(opportunity.dataAgeMs, RealTimeIntegrityPolicy.MAX_DATA_AGE_MS)
                    val finalized = V540FinalDecisionFlow.finalize(
                        engineDirection = engineDirection,
                        finalSignalScore = opportunity.finalSignalScore,
                        dataConfidence = opportunity.dataConfidenceScore,
                        riskScore = opportunity.riskScore,
                        price = opportunity.price,
                        technical = opportunity.technical,
                        candles = opportunity.candles,
                        freshnessScore = freshnessScore,
                        mtfAvailableCount = 0,
                        mtfExpectedCount = 0,
                        marketRegimeAdjustment = regimeAdjustment
                    )
                    opportunity.copy(
                        direction = when (finalized.publishedDirection) {
                            V531Direction.LONG -> "LONG"
                            V531Direction.SHORT -> "SHORT"
                            V531Direction.WATCH -> "NEUTRAL"
                        },
                        decisionState = if (opportunity.signalValidity == SignalValidity.VALID && finalized.publishedDirection != V531Direction.WATCH) DecisionState.VERIFIED_OPPORTUNITY else DecisionState.WATCH,
                        riskPlan = finalized.riskPlan,
                        rankingScore = finalized.rankingScore,
                        rankingStatus = finalized.rankingStatus,
                        mtfAvailableCount = finalized.mtfAvailableCount,
                        mtfExpectedCount = finalized.mtfExpectedCount,
                        mtfCompletenessPct = finalized.mtfCompletenessPct,
                        timingStatus = finalized.timingStatus,
                        setupType = finalized.setupType,
                        qualityClass = finalized.qualityClass,
                        mtfConsensusLabel = "GECİKMELİ MOD • MTF KAPALI",
                        marketRegime = regime?.regime?.label ?: "VERİ YOK",
                        marketRegimeConfidence = regime?.confidence ?: 0,
                        signalReasonCodes = (opportunity.signalReasonCodes + finalized.rrGateCode).distinct()
                    )
                }
            }
            // V5.1.48: only previously VERIFIED_HISTORICAL outcomes may influence current weights.
            // This happens before the current scan is persisted, preventing same-scan leakage.
            val optimized = try {
                historyRecorder.applyStrategyOptimization(regimeEnhanced)
            } catch (t: Throwable) {
                Log.e(TAG, "[BIST_SCAN] STRATEGY_OPTIMIZATION_FALLBACK ${t.message}", t)
                regimeEnhanced.map { it.copy(ensembleStatus = "FALLBACK_COMBINED • OPTIMIZATION_ERROR") }
            }
            onState(ScanState(
                status = ScanStatus.RUNNING,
                phase = ScanPhase.RANKING,
                scanMode = mode,
                progress = safeProgress(processed, total),
                processed = processed,
                total = total,
                successful = optimized.size,
                scanRun = run(ScanRunStatus.STARTED),
                timeframeLabel = timeframeLabel()
            ))
            val ranked = OpportunityRankingPolicy.sort(optimized)
            val completed = System.currentTimeMillis()
            val traced = ranked.map { opportunity ->
                opportunity.copy(
                    scanStartedAt = scanStartedAt,
                    scanCompletedAt = completed,
                    scanRunId = scanRunId,
                    snapshot = opportunity.snapshot?.copy(scanRunId = scanRunId)
                )
            }

            val successful = traced.size
            val providerDiagnostics = (provider as? ScanDiagnosticsSource)?.scanDiagnostics() ?: ProviderScanDiagnostics()
            val providerNoData = providerDiagnostics.noDataCount
            val providerErrors = providerDiagnostics.errorCount
            val structuralSkipped = maxOf((total - fetched).coerceAtLeast(0), providerNoData + providerErrors) + analyzed.analysisFailures
            val productionIntegrityWarnings = analyzed.integrityRejected
            val errorCount = structuralSkipped + if (mode == BistScanMode.REALTIME_ONLY) productionIntegrityWarnings else 0

            val runStatus = when (mode) {
                BistScanMode.REALTIME_ONLY -> when {
                    successful == total && structuralSkipped == 0 && productionIntegrityWarnings == 0 -> ScanRunStatus.COMPLETE
                    successful > 0 -> ScanRunStatus.PARTIAL
                    else -> ScanRunStatus.FAILED
                }
                BistScanMode.SESSION_CLOSE_ANALYSIS,
                BistScanMode.DELAYED_ANALYSIS -> when {
                    successful == fetched && structuralSkipped == 0 -> ScanRunStatus.COMPLETE
                    successful > 0 -> ScanRunStatus.PARTIAL
                    else -> ScanRunStatus.FAILED
                }
            }

            val rawFinalState = ScanState(
                status = ScanStatus.COMPLETED,
                phase = ScanPhase.COMPLETED,
                scanMode = mode,
                progress = 100,
                processed = total,
                total = total,
                successful = successful,
                skipped = structuralSkipped,
                noData = providerNoData,
                errors = providerErrors + analyzed.analysisFailures,
                integrityRejected = productionIntegrityWarnings,
                results = traced,
                scannedSymbols = stocks.map { it.symbol }.distinct(),
                failedSymbols = analyzed.failedSymbols,
                errorMessage = when {
                    successful > 0 -> null
                    mode == BistScanMode.REALTIME_ONLY && productionIntegrityWarnings > 0 ->
                        "Doğrulanmış anlık BIST verisi bulunmadığı için AL/SAT sonucu üretilmedi."
                    else -> "Hiçbir sembol geçerli analiz sonucu üretmedi."
                },
                scanRun = run(runStatus, completed, successful, errorCount),
                marketRegime = regime,
                timeframeLabel = timeframeLabel(),
                currentSymbol = (provider as? ScanProgressMetadataSource)?.currentScanSymbol()
            )

            val history = if (mode.observationOnly) {
                // Seans kapanışı/gecikmeli analiz, doğrulanmış anlık işlem sinyali geçmişine karıştırılmaz.
                HistoryRecordResult(inserted = 0)
            } else try {
                historyRecorder.record(rawFinalState)
            } catch (t: Throwable) {
                HistoryRecordResult(errorMessage = t.message ?: "Sinyal geçmişi kaydı başarısız")
            }
            val finalState = rawFinalState.copy(
                historyPersisted = history.inserted,
                historyError = history.errorMessage
            )

            Log.i(
                TAG,
                "[BIST_SCAN] ${runStatus.name} id=$scanRunId processed=$total/$total successful=$successful " +
                    "skipped=$structuralSkipped integrityWarning=$productionIntegrityWarnings history=${history.inserted}"
            )
            history.errorMessage?.let { Log.e(TAG, "[BIST_SCAN] HISTORY_ERROR $it") }
            onState(finalState)
            finalState
        } catch (ce: CancellationException) {
            val completed = System.currentTimeMillis()
            val cancelled = ScanState(
                status = ScanStatus.CANCELLED,
                phase = ScanPhase.STOPPED,
                scanMode = mode,
                progress = safeProgress(processed, total),
                processed = processed,
                total = total,
                successful = 0,
                skipped = 0,
                errorMessage = "Tarama kullanıcı tarafından durduruldu.",
                scanRun = run(ScanRunStatus.PARTIAL, completed),
                timeframeLabel = timeframeLabel()
            )
            onState(cancelled)
            throw ce
        } catch (t: Throwable) {
            val completed = System.currentTimeMillis()
            val message = when (t) {
                is ProviderException -> "${t.code.name} • ${t.message ?: "Provider hatası"}"
                else -> when {
                    t.message?.contains("tanımlı değil", true) == true -> "Canlı veri sağlayıcısı yapılandırılmamış."
                    t.message?.contains("sembol", true) == true -> "BIST sembol listesi alınamadı."
                    else -> t.message ?: "Tarama başlatılamadı."
                }
            }
            val noDataFailure = t is ProviderException && t.code in setOf(
                tr.borsatakip.v5.data.ProviderFailureCode.EMPTY_DATA,
                tr.borsatakip.v5.data.ProviderFailureCode.STALE_DATA,
                tr.borsatakip.v5.data.ProviderFailureCode.BIST_HISTORY_ERROR
            )
            val error = ScanState(
                status = ScanStatus.ERROR,
                phase = if (noDataFailure) ScanPhase.NO_DATA else ScanPhase.ERROR,
                scanMode = mode,
                progress = safeProgress(processed, total),
                processed = processed,
                total = total,
                successful = 0,
                skipped = 0,
                errorMessage = message,
                scanRun = run(ScanRunStatus.FAILED, completed, errors = 1),
                timeframeLabel = timeframeLabel()
            )
            Log.e(TAG, "[BIST_SCAN] ERROR $message", t)
            onState(error)
            error
        }
    }

    private data class AnalysisResult(
        val results: List<Opportunity>,
        val analysisFailures: Int,
        val integrityRejected: Int,
        val failedSymbols: List<String>
    )

    private suspend fun analyzeSafely(stocks: List<Stock>): AnalysisResult = withContext(Dispatchers.Default) {
        supervisorScope {
            data class Row(
                val symbol: String,
                val opportunity: Opportunity?,
                val integrityAccepted: Boolean,
                val analysisAttempted: Boolean
            )

            val analyzed = stocks.map { stock ->
                async {
                    val verdict = RealTimeIntegrityPolicy.validate(stock)
                    if (!verdict.accepted) {
                        Log.w(TAG, "[BIST_SCAN] INTEGRITY_${stock.symbol}: ${verdict.reason}")
                        if (mode == BistScanMode.REALTIME_ONLY) {
                            // Kritik güvenlik kapısı: anlık veri doğrulanmadıysa OpportunityEngine hiç çalışmaz.
                            return@async Row(stock.symbol, null, integrityAccepted = false, analysisAttempted = false)
                        }
                    }

                    try {
                        val scored = OpportunityEngine.score(stock)
                        val published = if (scored != null && mode.observationOnly && !verdict.accepted) {
                            OpportunityPublicationPolicy.delayedObservation(
                                scored,
                                if (mode == BistScanMode.SESSION_CLOSE_ANALYSIS) "SEANS KAPANIŞI • ${verdict.reason}" else verdict.reason
                            )
                        } else scored
                        Row(stock.symbol, published, verdict.accepted, analysisAttempted = true)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "[BIST_SCAN] SYMBOL_SKIPPED ${stock.symbol}: ${t.message}")
                        Row(stock.symbol, null, verdict.accepted, analysisAttempted = true)
                    }
                }
            }.awaitAll()

            val results = OpportunityRankingPolicy.sort(analyzed.mapNotNull { it.opportunity })
            AnalysisResult(
                results = results,
                analysisFailures = analyzed.count { it.analysisAttempted && it.opportunity == null },
                integrityRejected = analyzed.count { !it.integrityAccepted },
                failedSymbols = analyzed.filter { it.analysisAttempted && it.opportunity == null }.map { it.symbol }.distinct()
            )
        }
    }

    private suspend fun loadMarketRegime(): MarketRegimeEngine.Result? {
        for (symbol in BENCHMARK_SYMBOLS) {
            val candles = try {
                provider.fetchDailyHistory(symbol, maximumRange = false)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                emptyList()
            }
            if (candles.size >= 60) return MarketRegimeEngine.evaluate(symbol, candles)
        }
        return null
    }

    /**
     * MTF teyidi final ranking'den önce bütün adaylara aynı kuralla uygulanır.
     * "1 GÜN" girdisi seçili timeframe mumlarından türetilmez; gerçek günlük history ayrı alınır.
     * Eksik aggregate mumlar OhlcvResampler tarafından otomatik olarak dışarıda bırakılır.
     */
    private suspend fun applyMtfConsensus(items: List<Opportunity>, regime: MarketRegimeEngine.Result?): List<Opportunity> = supervisorScope {
        if (items.isEmpty()) return@supervisorScope items
        val semaphore = Semaphore(MTF_CONCURRENCY)
        val evaluated = items.map { opportunity ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val result = withTimeoutOrNull(MTF_TIMEOUT_MS) {
                        val now = System.currentTimeMillis()
                        val sourceKey = provider.mtfCacheSourceKey()
                        val oneMin = MtfHistoryCache.loadOptionalFresh(
                            sourceKey = sourceKey,
                            symbol = opportunity.symbol,
                            timeframe = "1m",
                            ttlMs = MTF_1M_CACHE_TTL_MS,
                            loader = { provider.fetchHistory(opportunity.symbol, now - 6L * 24 * 60 * 60 * 1000, now, 1) },
                            sourceKeyNow = { provider.mtfCacheSourceKey() }
                        )
                        val oneHour = MtfHistoryCache.loadOptionalFresh(
                            sourceKey = sourceKey,
                            symbol = opportunity.symbol,
                            timeframe = "60m",
                            ttlMs = MTF_1H_CACHE_TTL_MS,
                            loader = { provider.fetchHistory(opportunity.symbol, now - 120L * 24 * 60 * 60 * 1000, now, 60) },
                            sourceKeyNow = { provider.mtfCacheSourceKey() }
                        )
                        val oneDay = MtfHistoryCache.loadOptionalFresh(
                            sourceKey = sourceKey,
                            symbol = opportunity.symbol,
                            timeframe = "1d",
                            ttlMs = MTF_1D_CACHE_TTL_MS,
                            loader = { provider.fetchDailyHistory(opportunity.symbol, maximumRange = false) },
                            sourceKeyNow = { provider.mtfCacheSourceKey() }
                        )
                        val clean1 = OhlcvResampler.sanitize(oneMin)
                        val inputs = listOf(
                            MtfConsensusEngine.Input("1 DK", clean1, 0.08),
                            MtfConsensusEngine.Input("3 DK", OhlcvResampler.aggregate(clean1, 1, 3), 0.12),
                            MtfConsensusEngine.Input("5 DK", OhlcvResampler.aggregate(clean1, 1, 5), 0.15),
                            MtfConsensusEngine.Input("15 DK", OhlcvResampler.aggregate(clean1, 1, 15), 0.20),
                            MtfConsensusEngine.Input("1 SA", OhlcvResampler.sanitize(oneHour), 0.20),
                            MtfConsensusEngine.Input("1 GÜN", OhlcvResampler.sanitize(oneDay), 0.25)
                        )
                        MtfConsensusEngine.evaluate(inputs)
                    }
                    opportunity.symbol to result
                }
            }
        }.awaitAll().toMap()

        items.map { opportunity ->
            val mtf = evaluated[opportunity.symbol]
            val freshnessScore = V540FreshnessPolicy.score(opportunity.dataAgeMs, RealTimeIntegrityPolicy.MAX_DATA_AGE_MS)
            if (mtf == null || mtf.availableCount < 3) {
                val engineDirection = when (opportunity.direction.uppercase()) {
                    "LONG" -> V531Direction.LONG
                    "SHORT" -> V531Direction.SHORT
                    else -> V531Direction.WATCH
                }
                val regimeAdjustment = regime?.let {
                    MarketRegimeEngine.rankingAdjustment(opportunity.direction, it)
                } ?: 0
                val finalized = V540FinalDecisionFlow.finalize(
                    engineDirection = engineDirection,
                    finalSignalScore = opportunity.finalSignalScore,
                    dataConfidence = opportunity.dataConfidenceScore,
                    riskScore = opportunity.riskScore,
                    price = opportunity.price,
                    technical = opportunity.technical,
                    candles = opportunity.candles,
                    freshnessScore = freshnessScore,
                    mtfAvailableCount = mtf?.availableCount ?: 0,
                    mtfExpectedCount = 6,
                    marketRegimeAdjustment = regimeAdjustment
                )
                opportunity.copy(
                    direction = "NEUTRAL",
                    decisionState = when {
                        opportunity.signalValidity == SignalValidity.INSUFFICIENT -> DecisionState.INSUFFICIENT_DATA
                        opportunity.signalValidity == SignalValidity.REJECTED -> DecisionState.REJECTED
                        else -> DecisionState.WATCH
                    },
                    riskPlan = finalized.riskPlan,
                    timingStatus = finalized.timingStatus,
                    setupType = finalized.setupType,
                    qualityClass = finalized.qualityClass,
                    rankingScore = finalized.rankingScore,
                    rankingStatus = finalized.rankingStatus,
                    mtfAvailableCount = finalized.mtfAvailableCount,
                    mtfExpectedCount = finalized.mtfExpectedCount,
                    mtfCompletenessPct = finalized.mtfCompletenessPct,
                    mtfConsensusScore = mtf?.score,
                    mtfConsensusLabel = "MTF VERİ YOK",
                    marketRegime = regime?.regime?.label ?: "VERİ YOK",
                    marketRegimeConfidence = regime?.confidence ?: 0,
                    signalReasonCodes = (opportunity.signalReasonCodes + finalized.rrGateCode).distinct(),
                    scoreBreakdown = opportunity.scoreBreakdown + listOf(
                        "V540_MTF_AVAILABLE=${mtf?.availableCount ?: 0}/6",
                        "V540_RR_GATE=${finalized.rrGateCode}",
                        "V540_FINAL_FLOW=${V540FinalDecisionFlow.FLOW_VERSION}",
                        "RANKING_STATUS=${finalized.rankingStatus.name}",
                        "RANKING_SCORE=${finalized.rankingScore}"
                    ) + finalized.rankingAudit
                )
            } else {
                val context = V531ContextEngine.applyMtf(
                    longScore = opportunity.longScore,
                    shortScore = opportunity.shortScore,
                    dataConfidence = opportunity.dataConfidenceScore,
                    riskScore = opportunity.riskScore,
                    availableSignalWeight = opportunity.signalAvailableWeight,
                    mtfConsensusScore = mtf.score
                )
                val engineDirectionLabel = when (context.direction) {
                    V531Direction.LONG -> "LONG"
                    V531Direction.SHORT -> "SHORT"
                    V531Direction.WATCH -> "NEUTRAL"
                }
                val regimeAdjustment = regime?.let { MarketRegimeEngine.rankingAdjustment(engineDirectionLabel, it) } ?: 0
                val finalized = V540FinalDecisionFlow.finalize(
                    engineDirection = context.direction,
                    finalSignalScore = context.finalSignalScore,
                    dataConfidence = opportunity.dataConfidenceScore,
                    riskScore = context.riskScore,
                    price = opportunity.price,
                    technical = opportunity.technical,
                    candles = opportunity.candles,
                    freshnessScore = freshnessScore,
                    mtfAvailableCount = mtf.availableCount,
                    mtfExpectedCount = 6,
                    marketRegimeAdjustment = regimeAdjustment
                )
                val direction = when (finalized.publishedDirection) {
                    V531Direction.LONG -> "LONG"
                    V531Direction.SHORT -> "SHORT"
                    V531Direction.WATCH -> "NEUTRAL"
                }
                val state = when {
                    opportunity.signalValidity == SignalValidity.INSUFFICIENT -> DecisionState.INSUFFICIENT_DATA
                    opportunity.signalValidity == SignalValidity.REJECTED -> DecisionState.REJECTED
                    opportunity.signalValidity == SignalValidity.VALID && finalized.publishedDirection != V531Direction.WATCH -> DecisionState.VERIFIED_OPPORTUNITY
                    else -> DecisionState.WATCH
                }
                opportunity.copy(
                    score = context.finalSignalScore,
                    signalScore = context.finalSignalScore,
                    finalSignalScore = context.finalSignalScore,
                    longScore = context.longScore,
                    shortScore = context.shortScore,
                    direction = direction,
                    riskScore = context.riskScore,
                    decisionState = state,
                    riskPlan = finalized.riskPlan,
                    timingStatus = finalized.timingStatus,
                    setupType = finalized.setupType,
                    qualityClass = finalized.qualityClass,
                    rankingScore = finalized.rankingScore,
                    rankingStatus = finalized.rankingStatus,
                    mtfAvailableCount = finalized.mtfAvailableCount,
                    mtfExpectedCount = finalized.mtfExpectedCount,
                    mtfCompletenessPct = finalized.mtfCompletenessPct,
                    mtfConsensusScore = mtf.score,
                    mtfConsensusLabel = mtf.label,
                    marketRegime = regime?.regime?.label ?: "VERİ YOK",
                    marketRegimeConfidence = regime?.confidence ?: 0,
                    signalAvailableWeight = context.availableSignalWeight,
                    signalReasonCodes = (opportunity.signalReasonCodes + context.reasonCodes.map { it.name } + finalized.rrGateCode).distinct(),
                    signalConflictPenalty = context.conflictPenalty,
                    scoreBreakdown = opportunity.scoreBreakdown + listOf(
                        "V540_MTF_SCORE=${mtf.score}",
                        "V540_MTF_AVAILABLE=${mtf.availableCount}/6",
                        "V540_MTF_FINAL=${context.finalSignalScore}",
                        "V540_MTF_CONFLICT_PENALTY=${context.conflictPenalty}",
                        "V540_RR_GATE=${finalized.rrGateCode}",
                        "V540_FINAL_FLOW=${V540FinalDecisionFlow.FLOW_VERSION}",
                        "RANKING_STATUS=${finalized.rankingStatus.name}",
                        "RANKING_SCORE=${finalized.rankingScore}"
                    ) + finalized.rankingAudit
                )
            }
        }
    }


    private fun timeframeLabel(): String {
        val suffix = provider.id.substringAfterLast('_', "")
        return when {
            suffix.equals("1d", true) -> "1 GÜN"
            suffix.endsWith("m", true) -> suffix.dropLast(1).toIntOrNull()?.let { "$it DK" } ?: ""
            else -> ""
        }
    }

    companion object {
        const val TAG = "BIST_SCAN"
        val BENCHMARK_SYMBOLS = listOf("XU100", "XU100.IS")
        const val MTF_CONCURRENCY = 4
        const val MTF_TIMEOUT_MS = 12_000L
        const val MTF_1M_CACHE_TTL_MS = 30_000L
        const val MTF_1H_CACHE_TTL_MS = 5 * 60_000L
        const val MTF_1D_CACHE_TTL_MS = 30 * 60_000L
        fun safeProgress(processed: Int, total: Int): Int {
            if (total <= 0) return 0
            return ((processed.coerceAtLeast(0) * 100L) / total).toInt().coerceIn(0, 100)
        }
    }
}
