package tr.borsatakip.v5.analysis

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import tr.borsatakip.v5.data.BackendProvider
import tr.borsatakip.v5.data.MarketDataProvider
import tr.borsatakip.v5.data.MtfHistoryCache
import tr.borsatakip.v5.data.ViopStrategyDecisionService
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.RiskPlan
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.CalculationEngineMode
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopContractScanResult
import tr.borsatakip.v5.model.ViopContractOutcomeStatus
import tr.borsatakip.v5.model.ViopUniverseCompleteness
import tr.borsatakip.v5.model.ViopPublishedSignalDirection
import tr.borsatakip.v5.model.ViopAnalysisBias
import tr.borsatakip.v5.model.ViopOpportunity
import tr.borsatakip.v5.model.ViopScanError
import tr.borsatakip.v5.model.ViopScanProgress
import tr.borsatakip.v5.model.ViopScanResult
import tr.borsatakip.v5.analysis.v531.V531CalculationEngine
import tr.borsatakip.v5.analysis.v540.V540FreshnessPolicy
import tr.borsatakip.v5.analysis.v540.V540LiquidityEngine
import tr.borsatakip.v5.analysis.v531.V531CalculationInput
import tr.borsatakip.v5.analysis.v531.V531DataQualityInput
import tr.borsatakip.v5.analysis.v531.V531Direction
import tr.borsatakip.v5.analysis.v531.V531ContextEngine
import tr.borsatakip.v5.analysis.v531.ScoringConfig
import java.time.LocalDate
import java.util.UUID
import tr.borsatakip.v5.data.ScanProfile
import tr.borsatakip.v5.data.ScanTelemetry
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import java.util.Collections

class ViopScanner(
    private val backend: BackendProvider,
    private val marketProvider: MarketDataProvider? = null,
    private val strategyDecisionService: ViopStrategyDecisionService? = null
) {
    companion object {
        const val MIN_HISTORY_BARS = 220
        const val MIN_VOLUME = 1.0
        const val MIN_OPEN_INTEREST = 1L
        const val MAX_DATA_AGE_MS = 60_000L
        const val CONTRACT_CONCURRENCY = 4
        const val MTF_CONCURRENCY = 4
        const val MTF_TIMEOUT_MS = 12_000L
        const val MTF_1M_CACHE_TTL_MS = 30_000L
        const val MTF_1H_CACHE_TTL_MS = 5 * 60_000L

        fun isPastLastTradingAt(contract: ViopContract, nowMs: Long = System.currentTimeMillis()): Boolean =
            contract.lastTradingAt?.let { it <= nowMs } == true

        fun sortOpportunities(items: List<ViopOpportunity>): List<ViopOpportunity> =
            items.sortedWith(
                compareByDescending<ViopOpportunity> { it.rankingScore }
                    .thenByDescending { it.finalScore }
                    .thenBy { it.riskScore }
                    .thenBy { it.contract.symbol }
            )
    }

    suspend fun scan(onProgress: (ViopScanProgress) -> Unit = {}): ViopScanResult {
        val startedAt = System.currentTimeMillis()
        val runId = UUID.randomUUID().toString()
        val stageStarted = mutableMapOf<String, Long>()
        val cacheStats = MtfHistoryCache.Stats()
        fun stage(name: String, at: Long) {
            stageStarted[name] = (stageStarted[name] ?: 0L) + (System.currentTimeMillis() - at).coerceAtLeast(0L)
        }

        strategyDecisionService?.refreshOutcomes()
        val contractsStarted = System.currentTimeMillis()
        val universeResult = backend.loadViopUniverse()
        stage("contracts", contractsStarted)
        if (universeResult.isFailure) {
            val err = ViopScanError("-", codeOf(universeResult.exceptionOrNull(), "CONTRACT_ERROR"), universeResult.exceptionOrNull()?.message ?: "VİOP sözleşme evreni alınamadı.")
            val completedAt = System.currentTimeMillis()
            ScanTelemetry.record(ScanProfile(runId, startedAt, completedAt, stageStarted.toMap(), processed = 1, successful = 0, failed = 1, cacheHits = cacheStats.hits, cacheMisses = cacheStats.misses))
            return ViopScanResult(
                opportunities = emptyList(), errors = listOf(err), progress = ViopScanProgress(failed = 1),
                startedAt = startedAt, completedAt = completedAt, status = ScanRunStatus.FAILED,
                scanId = runId, universeAsOf = startedAt
            )
        }

        val universeSnapshot = universeResult.getOrThrow()
        val universeAsOf = universeSnapshot.universeAsOf ?: startedAt
        val universe = universeSnapshot.contracts.asSequence()
            .filterNot { it.isManual }
            .filter { it.lastTradingAt?.let { at -> at > startedAt } != false }
            .filter { it.expiryAt?.let { at -> at > startedAt } != false }
            .distinctBy { it.symbol.trim().uppercase() }
            .sortedBy { it.symbol }
            .toList()

        if (universe.isEmpty()) {
            val err = ViopScanError("-", "CONTRACT_ERROR", "Aktif Production VİOP sözleşme evreni boş.")
            val completedAt = System.currentTimeMillis()
            val progress = ViopScanProgress(
                providerTotal = universeSnapshot.providerTotal,
                fetchedCount = universeSnapshot.fetchedCount,
                fetchedUniqueCount = universeSnapshot.fetchedUniqueCount,
                activeUniqueFutures = universeSnapshot.activeUniqueFutures,
                universeCompleteness = universeSnapshot.completeness
            )
            return ViopScanResult(
                emptyList(), listOf(err), progress, startedAt, completedAt, ScanRunStatus.FAILED,
                scanId = runId, universeAsOf = universeAsOf, universeCompleteness = universeSnapshot.completeness,
                providerTotal = universeSnapshot.providerTotal, fetchedCount = universeSnapshot.fetchedCount,
                fetchedUniqueCount = universeSnapshot.fetchedUniqueCount, activeUniqueFutures = universeSnapshot.activeUniqueFutures
            )
        }

        val initialProgress = ViopScanProgress(
            total = universe.size,
            providerTotal = universeSnapshot.providerTotal,
            fetchedCount = universeSnapshot.fetchedCount,
            fetchedUniqueCount = universeSnapshot.fetchedUniqueCount,
            activeUniqueFutures = universeSnapshot.activeUniqueFutures,
            universeCompleteness = universeSnapshot.completeness
        )
        onProgress(initialProgress)

        val completedOutcomes = Collections.synchronizedList(mutableListOf<ViopContractScanResult>())
        val semaphore = Semaphore(CONTRACT_CONCURRENCY)
        val outcomes = supervisorScope {
            val tasks = universe.map { contract ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        scanOneContract(contract, startedAt)
                    }.also { outcome ->
                        val progress = synchronized(completedOutcomes) {
                            completedOutcomes += outcome
                            progressFromOutcomes(universe.size, completedOutcomes.toList(), universeSnapshot)
                        }
                        onProgress(progress)
                    }
                }
            }
            tasks.map { it.await() }
        }

        val candidates = outcomes.mapNotNull { it.opportunity }
        val regime = loadMarketRegime()
        val contextual = applyDecisionContext(candidates, regime, cacheStats)
        val optimized = try {
            strategyDecisionService?.apply(contextual) ?: contextual
        } catch (_: Throwable) {
            contextual.map { it.copy(ensembleStatus = "FALLBACK_COMBINED • OPTIMIZATION_ERROR") }
        }

        val rankingStarted = System.currentTimeMillis()
        val sanitized = optimized
            .filter { it.contract.validity == SignalValidity.VALID }
            .distinctBy { it.contract.symbol.trim().uppercase() }
        val sorted = sortOpportunities(sanitized)
        stage("ranking", rankingStarted)
        strategyDecisionService?.record(sorted)

        val finalBySymbol = sorted.associateBy { it.contract.symbol.trim().uppercase() }
        val finalOutcomes = outcomes.map { outcome ->
            if (outcome.status == ViopContractOutcomeStatus.PUBLISHED) {
                val finalOpportunity = finalBySymbol[outcome.contract.symbol.trim().uppercase()]
                if (finalOpportunity != null) outcome.copy(opportunity = finalOpportunity)
                else outcome.copy(
                    status = ViopContractOutcomeStatus.REJECTED,
                    opportunity = null,
                    errorCode = "PUBLICATION_FILTERED",
                    reason = "Nihai publication kapısı kontratı yayımlamadı."
                )
            } else outcome
        }

        val finalBaseProgress = progressFromOutcomes(universe.size, finalOutcomes, universeSnapshot)
        val accounting = ViopProductionPolicy.accounting(
            universeTotal = universe.size,
            opportunities = sorted,
            insufficient = finalBaseProgress.insufficient,
            rejected = finalBaseProgress.rejected,
            noData = finalBaseProgress.noData,
            unsupportedOptions = finalBaseProgress.unsupportedOptions
        )
        check(accounting.consistent) {
            "VIOP_ACCOUNTING_MISMATCH: total=${accounting.universeTotal} accounted=${accounting.accountedTotal} published=${accounting.published}"
        }

        val finalProgress = finalBaseProgress.copy(
            longCount = accounting.longCount,
            shortCount = accounting.shortCount,
            watchCount = accounting.watchCount,
            publishedCount = accounting.published
        )
        onProgress(finalProgress)

        val errors = finalOutcomes.mapNotNull { outcome ->
            val code = outcome.errorCode ?: return@mapNotNull null
            ViopScanError(outcome.contract.symbol, code, outcome.reason ?: code)
        }.toMutableList()
        if (universeSnapshot.completeness != ViopUniverseCompleteness.COMPLETE) {
            errors += ViopScanError(
                "-", "UNIVERSE_INCOMPLETE",
                universeSnapshot.completenessReason
            )
        }

        val completedAt = System.currentTimeMillis()
        ScanTelemetry.record(ScanProfile(
            runId = runId, startedAt = startedAt, completedAt = completedAt,
            stagesMs = stageStarted.toMap(), processed = accounting.accountedTotal,
            successful = accounting.published, failed = accounting.noData,
            cacheHits = cacheStats.hits, cacheMisses = cacheStats.misses
        ))
        val status = when {
            universeSnapshot.completeness != ViopUniverseCompleteness.COMPLETE -> ScanRunStatus.PARTIAL
            sorted.isEmpty() && finalProgress.noData > 0 -> ScanRunStatus.FAILED
            finalProgress.noData > 0 || finalProgress.insufficient > 0 || finalProgress.rejected > 0 || finalProgress.unsupportedOptions > 0 -> ScanRunStatus.PARTIAL
            else -> ScanRunStatus.COMPLETE
        }
        return ViopScanResult(
            opportunities = sorted,
            errors = errors,
            progress = finalProgress,
            startedAt = startedAt,
            completedAt = completedAt,
            status = status,
            scanId = runId,
            universeAsOf = universeAsOf,
            universeCompleteness = universeSnapshot.completeness,
            providerTotal = universeSnapshot.providerTotal,
            fetchedCount = universeSnapshot.fetchedCount,
            fetchedUniqueCount = universeSnapshot.fetchedUniqueCount,
            activeUniqueFutures = universeSnapshot.activeUniqueFutures,
            outcomes = finalOutcomes
        )
    }

    private fun progressFromOutcomes(
        total: Int,
        outcomes: List<ViopContractScanResult>,
        universe: tr.borsatakip.v5.model.ViopUniverseSnapshot
    ): ViopScanProgress {
        val published = outcomes.count { it.status == ViopContractOutcomeStatus.PUBLISHED }
        val insufficient = outcomes.count { it.status == ViopContractOutcomeStatus.INSUFFICIENT_DATA }
        val rejected = outcomes.count { it.status == ViopContractOutcomeStatus.REJECTED }
        val noData = outcomes.count { it.status == ViopContractOutcomeStatus.NO_DATA }
        val unsupported = outcomes.count { it.status == ViopContractOutcomeStatus.UNSUPPORTED_OPTION }
        return ViopScanProgress(
            total = total,
            quoteSuccess = outcomes.count { it.quoteSuccess },
            historySuccess = outcomes.count { it.historySuccess },
            analyzed = outcomes.count { it.analyzed },
            insufficient = insufficient,
            eliminated = rejected + unsupported,
            failed = noData,
            rejected = rejected,
            noData = noData,
            unsupportedOptions = unsupported,
            publishedCount = published,
            providerTotal = universe.providerTotal,
            fetchedCount = universe.fetchedCount,
            fetchedUniqueCount = universe.fetchedUniqueCount,
            activeUniqueFutures = universe.activeUniqueFutures,
            universeCompleteness = universe.completeness
        )
    }

    private suspend fun scanOneContract(contract: ViopContract, scanStartedAt: Long): ViopContractScanResult {
        fun outcome(
            status: ViopContractOutcomeStatus,
            code: String,
            reason: String,
            quoteSuccess: Boolean = false,
            historySuccess: Boolean = false,
            analyzed: Boolean = false
        ) = ViopContractScanResult(
            contract = contract, status = status, errorCode = code, reason = reason,
            quoteSuccess = quoteSuccess, historySuccess = historySuccess, analyzed = analyzed
        )

        when (ViopProductionPolicy.contractDisposition(contract)) {
            ViopProductionPolicy.ContractDisposition.UNSUPPORTED_OPTION -> return outcome(
                ViopContractOutcomeStatus.UNSUPPORTED_OPTION,
                "UNSUPPORTED_OPTION",
                "Production yayını vadeli işlem kontratlarıyla sınırlıdır; opsiyon analiz modeli etkin değildir."
            )
            ViopProductionPolicy.ContractDisposition.INSUFFICIENT_DATA -> return outcome(
                ViopContractOutcomeStatus.INSUFFICIENT_DATA, "CONTRACT_INSUFFICIENT", contract.validityReason
            )
            ViopProductionPolicy.ContractDisposition.REJECTED -> return outcome(
                ViopContractOutcomeStatus.REJECTED, "CONTRACT_NOT_VALID", contract.validityReason
            )
            ViopProductionPolicy.ContractDisposition.ANALYZE -> Unit
        }
        if (isPastLastTradingAt(contract, scanStartedAt)) return outcome(
            ViopContractOutcomeStatus.REJECTED, "EXPIRED", "Son işlem zamanı geçmiş sözleşme taramaya alınmadı."
        )

        val quoteResult = backend.loadViopQuote(contract.symbol)
        if (quoteResult.isFailure) return outcome(
            ViopContractOutcomeStatus.NO_DATA,
            codeOf(quoteResult.exceptionOrNull(), "QUOTE_ERROR"),
            quoteResult.exceptionOrNull()?.message ?: "Quote alınamadı."
        )
        val quote = quoteResult.getOrThrow()
        val nowQuote = System.currentTimeMillis()
        val quoteRejection = ViopProductionPolicy.quoteRejectionCode(contract, quote, nowQuote)
        if (quoteRejection != null) return outcome(
            ViopContractOutcomeStatus.REJECTED,
            quoteRejection,
            "Quote publication kapısını geçemedi: $quoteRejection",
            quoteSuccess = true
        )

        val historyResult = backend.loadViopHistory(contract.symbol)
        if (historyResult.isFailure) {
            val code = codeOf(historyResult.exceptionOrNull(), "HISTORY_ERROR")
            return if (code == "INSUFFICIENT_HISTORY") outcome(
                ViopContractOutcomeStatus.INSUFFICIENT_DATA, code,
                historyResult.exceptionOrNull()?.message ?: "History yetersiz.", quoteSuccess = true
            ) else outcome(
                ViopContractOutcomeStatus.NO_DATA, code,
                historyResult.exceptionOrNull()?.message ?: "History alınamadı.", quoteSuccess = true
            )
        }
        val candles = historyResult.getOrThrow()
        if (candles.size < MIN_HISTORY_BARS) return outcome(
            ViopContractOutcomeStatus.INSUFFICIENT_DATA,
            "INSUFFICIENT_HISTORY", "${candles.size} mum; minimum $MIN_HISTORY_BARS.",
            quoteSuccess = true, historySuccess = true
        )

        val volume = quote.volume ?: contract.volume
        val oi = quote.openInterest ?: contract.openInterest
        if ((volume == null || volume < MIN_VOLUME) || (oi == null || oi < MIN_OPEN_INTEREST)) return outcome(
            ViopContractOutcomeStatus.REJECTED, "LOW_LIQUIDITY", "Hacim/açık pozisyon eşiği sağlanmadı.",
            quoteSuccess = true, historySuccess = true
        )

        val publishAge = System.currentTimeMillis() - quote.exchangeTimestamp
        if (publishAge < -tr.borsatakip.v5.data.RealTimeIntegrityPolicy.MAX_FUTURE_CLOCK_SKEW_MS || publishAge > MAX_DATA_AGE_MS) return outcome(
            ViopContractOutcomeStatus.REJECTED, "STALE_DATA", "History sonrası quote tazeliğini kaybetti.",
            quoteSuccess = true, historySuccess = true
        )
        if (contract.lastTradingAt == null) return outcome(
            ViopContractOutcomeStatus.INSUFFICIENT_DATA, "EXPIRY_UNVERIFIED", "Gerçek son işlem zamanı sağlayıcı tarafından verilmedi.",
            quoteSuccess = true, historySuccess = true
        )

        val technical = TechnicalAnalyzer.analyze(candles)
        val opportunity = try {
            score(contract, quote, candles, technical, publishAge.coerceAtLeast(0L))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return outcome(
                ViopContractOutcomeStatus.NO_DATA, "ANALYSIS_ERROR", t.message ?: "Analiz üretilemedi.",
                quoteSuccess = true, historySuccess = true
            )
        }
        return when (opportunity.validity) {
            SignalValidity.INSUFFICIENT -> outcome(
                ViopContractOutcomeStatus.INSUFFICIENT_DATA, "INSUFFICIENT_ANALYSIS", "Teknik/karar motoru yeterli veri üretemedi.",
                quoteSuccess = true, historySuccess = true, analyzed = true
            )
            SignalValidity.REJECTED -> outcome(
                ViopContractOutcomeStatus.REJECTED, "ANALYSIS_REJECTED", "Analiz veri kalitesi publication kapısını geçemedi.",
                quoteSuccess = true, historySuccess = true, analyzed = true
            )
            SignalValidity.VALID, SignalValidity.WATCH -> ViopContractScanResult(
                contract = contract,
                status = ViopContractOutcomeStatus.PUBLISHED,
                opportunity = opportunity,
                quoteSuccess = true,
                historySuccess = true,
                analyzed = true
            )
        }
    }

    private fun score(
        contract: ViopContract,
        quote: tr.borsatakip.v5.model.ViopQuote,
        candles: List<Candle>,
        t: tr.borsatakip.v5.model.TechnicalSnapshot,
        age: Long
    ): ViopOpportunity {
        check(contract.validity == SignalValidity.VALID) { "CONTRACT_NOT_VALID: ${contract.symbol} analiz kapısına geçemez." }
        val price = DecisionPricePolicy.historyBarPrice(candles)
            ?: error("HISTORY_ERROR: Karar fiyatı için geçerli kapanmış history mumu yok.")
        val structure = ViopBreakoutPolicy.evaluate(candles, t.volumeRatio)
        val volumeAnomalyPct = structure.volumeAnomalyPct
        val breakoutState = structure.state
        val breakoutConfirmed = structure.confirmed
        val expiryRisk = expiryRisk(contract.lastTradingAt)
        val spreadPct = if (quote.bid != null && quote.ask != null && quote.bid > 0.0 && quote.ask >= quote.bid) {
            val mid = (quote.bid + quote.ask) / 2.0
            if (mid > 0.0) ((quote.ask - quote.bid) / mid) * 100.0 else null
        } else null
        val liquidity = V540LiquidityEngine.score(
            t.volumeRatio, spreadPct, candles.lastOrNull()?.volume, candles.dropLast(1).map { it.volume }
        ) ?: 0
        val atr = t.atr14?.takeIf { it.isFinite() && it > 0.0 }
        val support = t.support?.takeIf { it.isFinite() && it > 0.0 }
        val resistance = t.resistance?.takeIf { it.isFinite() && it > 0.0 }
        val structureDistanceAtr = if (atr != null) {
            listOfNotNull(support, resistance).minOfOrNull { abs(price - it) }?.div(atr)
        } else null
        val volumeAvailable = (quote.volume ?: contract.volume)?.let { it.isFinite() && it > 0.0 } == true
        val oiAvailable = (quote.openInterest ?: contract.openInterest ?: 0L) > 0L
        val v531 = V531CalculationEngine.calculate(
            V531CalculationInput(
                candles = candles,
                technical = t,
                currentPrice = price,
                dataQuality = V531DataQualityInput(
                    dataAgeMs = age,
                    candleCount = candles.size,
                    candlesValid = candles.size >= MIN_HISTORY_BARS,
                    quoteValid = quote.price.isFinite() && quote.price > 0.0 && quote.exchangeTimestamp > 0L,
                    providerHealthy = quote.marketDataMetadata?.let { it.state == tr.borsatakip.v5.model.MarketDataState.LIVE && !it.isFallback && !it.isOffline } == true,
                    symbolResolved = contract.symbol.isNotBlank() && quote.symbol.equals(contract.symbol, true),
                    volumeAvailable = volumeAvailable,
                    openInterestApplicable = true,
                    openInterestAvailable = oiAvailable
                ),
                // OI directional evidence is used only when the provider supplies a point-in-time change.
                openInterestChangePct = quote.openInterestChangePct,
                openInterestAsOfTimestamp = quote.openInterestAsOfTimestamp,
                spreadPct = spreadPct,
                liquidityScore = liquidity,
                structureDistanceAtr = structureDistanceAtr,
                contractRiskPct = expiryRisk,
                evaluationTimestamp = candles.lastOrNull()?.timestamp,
                technicalAsOfTimestamp = candles.lastOrNull()?.timestamp
            )
        )
        val longScore = v531.longScore
        val shortScore = v531.shortScore
        val viopSpecific = ViopSpecificAnalysis.evaluate(
            contract = contract, quote = quote, technical = t,
            baseLong = longScore, baseShort = shortScore, expiryRisk = expiryRisk, spreadPct = spreadPct
        )
        val final = v531.finalSignalScore
        val risk = v531.riskScore
        val dataConfidence = v531.dataConfidence
        val metadata = quote.marketDataMetadata
        val metadataLive = metadata?.state == tr.borsatakip.v5.model.MarketDataState.LIVE && !metadata.isFallback && !metadata.isOffline
        val technicalReady = t.analysisStatus == "OK"
        val validity = when {
            !technicalReady -> SignalValidity.INSUFFICIENT
            !metadataLive -> SignalValidity.REJECTED
            dataConfidence >= 60 -> SignalValidity.VALID
            else -> SignalValidity.WATCH
        }
        val freshnessScore = V540FreshnessPolicy.score(age, MAX_DATA_AGE_MS)
        val finalized = V540FinalDecisionFlow.finalize(
            engineDirection = v531.decision,
            finalSignalScore = final,
            dataConfidence = dataConfidence,
            riskScore = risk,
            price = price,
            technical = t,
            candles = candles,
            freshnessScore = freshnessScore,
            mtfAvailableCount = 0,
            mtfExpectedCount = 0
        )
        val direction = if (validity == SignalValidity.VALID) when (finalized.publishedDirection) {
            V531Direction.LONG -> "LONG"
            V531Direction.SHORT -> "SHORT"
            V531Direction.WATCH -> "NEUTRAL"
        } else "NEUTRAL"
        val rr1 = finalized.rr1
        val rr2 = finalized.rr2
        val riskPlan = finalized.riskPlan
        val rankingScore = finalized.rankingScore
        val decisionState = if (validity == SignalValidity.VALID && finalized.publishedDirection != V531Direction.WATCH) {
            DecisionState.VERIFIED_OPPORTUNITY
        } else DecisionState.WATCH
        val reason = buildList {
            add("${v531.engineVersion} yayın=$direction • VİOP teknik eğilim=${viopSpecific.direction} • skor $final/100")
            add("LONG=$longScore • SHORT=$shortScore")
            add("Veri güveni=$dataConfidence/100")
            add("Risk=$risk/100")
            add("Likidite=$liquidity/100")
            add("Vade riski=$expiryRisk/100")
            add("Sinyal kapsamı=${v531.availableSignalWeight}/100")
            when {
                !oiAvailable -> add("OI verisi yok")
                quote.openInterestChangePct != null -> add("OI değişimi=${"%+.2f".format(quote.openInterestChangePct)}%")
                else -> add("OI seviyesi mevcut; yön değişimi N/A")
            }
            volumeAnomalyPct?.let { add("Hacim anomalisi=${"%+.0f".format(it)}%") }
            if (breakoutState != "YOK") add("$breakoutState${if (breakoutConfirmed) " • hacim teyitli" else " • hacim teyitsiz"}")
            if (v531.reasonCodes.isNotEmpty()) add("Neden=${v531.reasonCodes.joinToString(",") { it.name }}")
            add("RR kapısı=${finalized.rrGateCode}")
            add("Sıralama=$rankingScore/100")
            add("Veri yaşı=${age / 1000}s")
        }.joinToString(" • ")
        return ViopOpportunity(
            contract = contract, quote = quote, candles = candles, technical = t,
            technicalScore = final, riskScore = risk, liquidityScore = liquidity,
            expiryRisk = expiryRisk, longScore = longScore, shortScore = shortScore,
            finalScore = final, direction = direction, signalReason = reason,
            validity = validity, dataAgeMs = age, historyCandleCount = candles.size,
            decisionState = decisionState, dataConfidenceScore = dataConfidence, riskPlan = riskPlan, rankingScore = rankingScore,
            rankingStatus = finalized.rankingStatus, mtfAvailableCount = finalized.mtfAvailableCount,
            mtfExpectedCount = finalized.mtfExpectedCount, mtfCompletenessPct = finalized.mtfCompletenessPct,
            volumeAnomalyPct = volumeAnomalyPct, breakoutState = breakoutState, breakoutConfirmed = breakoutConfirmed,
            decisionPrice = price, calculationEngineVersion = v531.engineVersion, calculationEngineMode = CalculationEngineMode.LOCAL,
            signalAvailableWeight = v531.availableSignalWeight,
            signalReasonCodes = (v531.reasonCodes.map { it.name } + finalized.rrGateCode).distinct(), signalConflictPenalty = v531.conflictPenalty,
            analysisLongScore = viopSpecific.longScore, analysisShortScore = viopSpecific.shortScore,
            analysisDirection = viopSpecific.direction,
            scoreExplanation = v531.auditLines.filter { it.startsWith("COMPONENT=") } + viopSpecific.reasons +
                listOf("VIOP_RISK_CONTEXT=${viopSpecific.riskAdjustment}"),
            marketDataMetadata = quote.marketDataMetadata,
            publishedSignalDirection = if (decisionState == DecisionState.VERIFIED_OPPORTUNITY) {
                if (direction == "LONG") ViopPublishedSignalDirection.LONG else ViopPublishedSignalDirection.SHORT
            } else ViopPublishedSignalDirection.WATCH,
            analysisBias = when (viopSpecific.direction.uppercase()) {
                "LONG" -> ViopAnalysisBias.LONG
                "SHORT" -> ViopAnalysisBias.SHORT
                else -> ViopAnalysisBias.NEUTRAL
            }
        )
    }


    private suspend fun loadMarketRegime(): MarketRegimeEngine.Result? {
        val provider = marketProvider ?: return null
        for (symbol in listOf("XU100", "XU100.IS")) {
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

    /** VİOP MTF bütün uygun adaylara final ranking'den önce aynı kuralla uygulanır. */
    private suspend fun applyDecisionContext(
        items: List<ViopOpportunity>,
        regime: MarketRegimeEngine.Result?,
        cacheStats: MtfHistoryCache.Stats
    ): List<ViopOpportunity> = supervisorScope {
        if (items.isEmpty()) return@supervisorScope items

        val semaphore = Semaphore(MTF_CONCURRENCY)
        val evaluated = items.map { opportunity ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val mtf = withTimeoutOrNull(MTF_TIMEOUT_MS) {
                        val symbol = opportunity.contract.symbol
                        val sourceKey = backend.mtfCacheSourceKey()
                        val oneMin = MtfHistoryCache.loadOptionalFresh(
                            sourceKey = sourceKey,
                            symbol = symbol,
                            timeframe = "1m",
                            ttlMs = MTF_1M_CACHE_TTL_MS,
                            loader = { backend.loadViopHistoryFlexible(symbol, "7d", "1m", 0).getOrThrow() },
                            stats = cacheStats,
                            sourceKeyNow = { backend.mtfCacheSourceKey() }
                        )
                        val oneHour = MtfHistoryCache.loadOptionalFresh(
                            sourceKey = sourceKey,
                            symbol = symbol,
                            timeframe = "60m",
                            ttlMs = MTF_1H_CACHE_TTL_MS,
                            loader = { backend.loadViopHistoryFlexible(symbol, "120d", "60m", 0).getOrThrow() },
                            stats = cacheStats,
                            sourceKeyNow = { backend.mtfCacheSourceKey() }
                        )
                        val clean1 = OhlcvResampler.sanitize(oneMin)
                        MtfConsensusEngine.evaluate(
                            listOf(
                                MtfConsensusEngine.Input("1 DK", clean1, 0.08),
                                MtfConsensusEngine.Input("3 DK", OhlcvResampler.aggregate(clean1, 1, 3), 0.12),
                                MtfConsensusEngine.Input("5 DK", OhlcvResampler.aggregate(clean1, 1, 5), 0.15),
                                MtfConsensusEngine.Input("15 DK", OhlcvResampler.aggregate(clean1, 1, 15), 0.20),
                                MtfConsensusEngine.Input("1 SA", OhlcvResampler.sanitize(oneHour), 0.20),
                                MtfConsensusEngine.Input("1 GÜN", OhlcvResampler.sanitize(opportunity.candles), 0.25)
                            )
                        )
                    }
                    opportunity.contract.symbol to mtf
                }
            }
        }.map { it.await() }.toMap()

        items.map { opportunity ->
            val mtf = evaluated[opportunity.contract.symbol]?.takeIf { it.availableCount >= 3 }
            val context = mtf?.let {
                V531ContextEngine.applyMtf(
                    longScore = opportunity.longScore,
                    shortScore = opportunity.shortScore,
                    dataConfidence = opportunity.dataConfidenceScore,
                    riskScore = opportunity.riskScore,
                    availableSignalWeight = opportunity.signalAvailableWeight,
                    mtfConsensusScore = it.score
                )
            }
            val engineDirection = context?.direction ?: when (opportunity.direction) {
                "LONG" -> V531Direction.LONG
                "SHORT" -> V531Direction.SHORT
                else -> V531Direction.WATCH
            }
            val finalSignal = context?.finalSignalScore ?: opportunity.finalScore
            val finalRisk = context?.riskScore ?: opportunity.riskScore
            val engineDirectionLabel = when (engineDirection) {
                V531Direction.LONG -> "LONG"
                V531Direction.SHORT -> "SHORT"
                V531Direction.WATCH -> "NEUTRAL"
            }
            val regimeAdj = regime?.let { MarketRegimeEngine.rankingAdjustment(engineDirectionLabel, it) } ?: 0
            val freshnessScore = V540FreshnessPolicy.score(opportunity.dataAgeMs, MAX_DATA_AGE_MS)
            val finalized = V540FinalDecisionFlow.finalize(
                engineDirection = engineDirection,
                finalSignalScore = finalSignal,
                dataConfidence = opportunity.dataConfidenceScore,
                riskScore = finalRisk,
                price = opportunity.decisionPrice,
                technical = opportunity.technical,
                candles = opportunity.candles,
                freshnessScore = freshnessScore,
                mtfAvailableCount = mtf?.availableCount ?: 0,
                mtfExpectedCount = 6,
                marketRegimeAdjustment = regimeAdj
            )
            val direction = when (finalized.publishedDirection) {
                V531Direction.LONG -> "LONG"
                V531Direction.SHORT -> "SHORT"
                V531Direction.WATCH -> "NEUTRAL"
            }
            val decisionState = when {
                opportunity.validity == SignalValidity.INSUFFICIENT -> DecisionState.INSUFFICIENT_DATA
                opportunity.validity == SignalValidity.REJECTED -> DecisionState.REJECTED
                opportunity.contract.validity == SignalValidity.VALID && opportunity.validity == SignalValidity.VALID && finalized.publishedDirection != V531Direction.WATCH -> DecisionState.VERIFIED_OPPORTUNITY
                else -> DecisionState.WATCH
            }
            val suffix = buildList {
                if (mtf != null) add("MTF=${mtf.label} (${mtf.score})")
                if (context != null) add("${ScoringConfig.ENGINE_VERSION}=${context.finalSignalScore}/100 • conflict=${context.conflictPenalty}")
                add("RR_GATE=${finalized.rrGateCode}")
                add("FLOW=${V540FinalDecisionFlow.FLOW_VERSION}")
                if (regime != null && regime.regime != MarketRegimeEngine.Regime.UNKNOWN) add("Rejim=${regime.regime.label} (${regime.confidence})")
            }.joinToString(" • ")
            opportunity.copy(
                longScore = context?.longScore ?: opportunity.longScore,
                shortScore = context?.shortScore ?: opportunity.shortScore,
                finalScore = finalSignal,
                technicalScore = finalSignal,
                riskScore = finalRisk,
                direction = direction,
                decisionState = decisionState,
                riskPlan = finalized.riskPlan,
                rankingScore = finalized.rankingScore,
                rankingStatus = finalized.rankingStatus,
                mtfAvailableCount = finalized.mtfAvailableCount,
                mtfExpectedCount = finalized.mtfExpectedCount,
                mtfCompletenessPct = finalized.mtfCompletenessPct,
                mtfConsensusScore = mtf?.score,
                mtfConsensusLabel = mtf?.label ?: "MTF VERİ YOK",
                marketRegime = regime?.regime?.label ?: "VERİ YOK",
                marketRegimeConfidence = regime?.confidence ?: 0,
                signalAvailableWeight = context?.availableSignalWeight ?: opportunity.signalAvailableWeight,
                signalReasonCodes = if (context == null) (opportunity.signalReasonCodes + finalized.rrGateCode).distinct() else (opportunity.signalReasonCodes + context.reasonCodes.map { it.name } + finalized.rrGateCode).distinct(),
                signalConflictPenalty = context?.conflictPenalty ?: opportunity.signalConflictPenalty,
                signalReason = if (suffix.isBlank()) opportunity.signalReason else opportunity.signalReason + " • " + suffix,
                publishedSignalDirection = if (decisionState == DecisionState.VERIFIED_OPPORTUNITY) {
                    if (direction == "LONG") ViopPublishedSignalDirection.LONG else ViopPublishedSignalDirection.SHORT
                } else ViopPublishedSignalDirection.WATCH,
                analysisBias = when (opportunity.analysisDirection.uppercase()) {
                    "LONG" -> ViopAnalysisBias.LONG
                    "SHORT" -> ViopAnalysisBias.SHORT
                    else -> ViopAnalysisBias.NEUTRAL
                }
            )
        }
    }

    private fun expiryRisk(lastTradingAt: Long?): Int {
        val at = lastTradingAt ?: return 100
        val days = ChronoUnit.DAYS.between(LocalDate.now(), java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()).toInt()
        return when { days < 0 -> 100; days <= 5 -> 90; days <= 10 -> 75; days <= 20 -> 55; days <= 40 -> 35; else -> 20 }
    }

    private fun codeOf(t: Throwable?, fallback: String): String {
        val text = t?.message.orEmpty().uppercase()
        return listOf("AUTH_ERROR","CONTRACT_ERROR","CONTRACT_INSUFFICIENT","CONTRACT_NOT_VALID","UNSUPPORTED_OPTION","QUOTE_ERROR","HISTORY_ERROR","INSUFFICIENT_HISTORY","STALE_DATA","SYMBOL_MISMATCH","INVALID_PRICE","INVALID_TIMESTAMP","DELAY_UNVERIFIED","METADATA_MISSING","DATA_NOT_LIVE","ANALYSIS_ERROR","INSUFFICIENT_ANALYSIS","ANALYSIS_REJECTED","LOW_LIQUIDITY","EXPIRED","RATE_LIMIT","SERVER_ERROR").firstOrNull { text.contains(it) } ?: fallback
    }
}
