package tr.borsatakip.v5.model

import android.os.SystemClock

enum class DataMode { REALTIME, DELAYED, EOD, UNVERIFIED }
enum class MarketDataState { LIVE, DELAYED, FALLBACK, STALE, OFFLINE, UNKNOWN }

data class MarketDataMetadata(
    val providerId: String,
    val source: String,
    val marketDataTimestamp: Long,
    val deviceReceivedAt: Long,
    val isLive: Boolean,
    val isDelayed: Boolean,
    val delayDurationMs: Long?,
    val lastSuccessfulUpdateAt: Long,
    val state: MarketDataState,
    val isFallback: Boolean = false,
    val isOffline: Boolean = false,
    val qualityReason: String = ""
) : java.io.Serializable
enum class SignalValidity { VALID, WATCH, INSUFFICIENT, REJECTED }
enum class ScanRunStatus { STARTED, PARTIAL, COMPLETE, FAILED }
enum class DecisionState { VERIFIED_OPPORTUNITY, WATCH, INSUFFICIENT_DATA, REJECTED }
enum class ScanMode { LIVE, PERIODIC, MANUAL }
enum class CalculationEngineMode { LOCAL, REMOTE, LEGACY }
enum class RankingStatus { CALCULATED, BLOCKED_RR, INSUFFICIENT_DATA, INVALID }

enum class ViopContractType(val label: String, val wireValue: String) {
    FUTURE("Vadeli İşlem", "FUTURE"),
    OPTION("Opsiyon", "OPTION"),
    UNKNOWN("Bilinmeyen", "UNKNOWN");

    override fun toString(): String = label

    companion object {
        fun parse(raw: String?): ViopContractType {
            val normalized = raw.orEmpty()
                .trim()
                .uppercase(java.util.Locale.ROOT)
                .replace('İ', 'I')
                .replace('Ş', 'S')
                .replace('Ğ', 'G')
                .replace('Ü', 'U')
                .replace('Ö', 'O')
                .replace('Ç', 'C')
                .replace(Regex("[^A-Z0-9]+"), "_")
                .trim('_')
            return when {
                normalized in setOf("FUTURE", "FUTURES", "VADELI", "VADELI_ISLEM", "FUTURE_CONTRACT") -> FUTURE
                normalized in setOf("OPTION", "OPTIONS", "OPSIYON", "OPTION_CONTRACT") -> OPTION
                else -> UNKNOWN
            }
        }
    }
}

enum class ViopUniverseCompleteness { COMPLETE, INCOMPLETE, UNVERIFIED }

enum class ViopContractOutcomeStatus {
    PUBLISHED,
    INSUFFICIENT_DATA,
    REJECTED,
    NO_DATA,
    UNSUPPORTED_OPTION
}

enum class ViopPublishedSignalDirection { LONG, SHORT, WATCH }
enum class ViopAnalysisBias { LONG, SHORT, NEUTRAL }

data class RiskPlan(
    val entry: Double,
    val stop: Double?,
    val target1: Double?,
    val target2: Double?,
    val rr1: Double?,
    val rr2: Double?
) : java.io.Serializable

data class Candle(val timestamp:Long,val open:Double,val high:Double,val low:Double,val close:Double,val volume:Double) : java.io.Serializable

data class Stock(
    val symbol:String,
    val companyName:String?,
    val candles:List<Candle>,
    val source:String,
    val dataTimestamp:Long,
    val isRealtime:Boolean = false,
    val delaySeconds:Int? = null,
    val currentSessionIncluded:Boolean = false,
    val receivedAt:Long = System.currentTimeMillis(),
    val receivedElapsedRealtime:Long = SystemClock.elapsedRealtime(),
    val quotePrice:Double? = null,
    val currency:String? = null,
    val market:String = "BIST",
    val historySymbol:String? = null,
    val interval:String = "1d",
    val exchangeTimezone:String? = null,
    val sessionId:String? = null,
    val lastBarTime:Long = 0L,
    val lastBarClosed:Boolean? = null,
    val previousClose:Double? = null,
    val bid:Double? = null,
    val ask:Double? = null,
    val marketDataMetadata:MarketDataMetadata? = null
) { val exchangeTimestamp:Long get() = dataTimestamp }

data class TechnicalSnapshot(
    val ema20:Double?, val ema50:Double?, val ema200:Double?, val rsi14:Double?,
    val macd:Double?, val macdSignal:Double?, val bbUpper:Double?, val bbLower:Double?,
    val atr14:Double?, val vwap:Double?, val volumeRatio:Double?, val support:Double?, val resistance:Double?,
    val vwma:Double? = null, val recommendation:Double? = null,
    val vwma20:Double? = null, val sessionVwap:Double? = null,
    val analysisStatus:String = "OK",
    val insufficientReason:String? = null,
    val availableBars:Int = 0,
    val requiredBars:Int = 0
) : java.io.Serializable

data class ScanRun(
    val scanRunId:String, val scanStartedAt:Long, val scanCompletedAt:Long? = null,
    val providerId:String, val status:ScanRunStatus, val count:Int = 0, val errorCount:Int = 0
)

data class DataSnapshot(
    val provider:String, val symbol:String, val price:Double,
    val exchangeTimestamp:Long, val receivedAt:Long, val dataMode:DataMode,
    val delaySeconds:Int?, val currentSessionIncluded:Boolean,
    val marketDataMetadata:MarketDataMetadata? = null
) : java.io.Serializable

data class OpportunitySnapshot(
    val provider:String, val symbol:String, val price:Double,
    val exchangeTimestamp:Long, val receivedAt:Long, val dataMode:DataMode,
    val dataConfidence:Int, val technical:TechnicalSnapshot, val finalSignalScore:Int,
    val scanRunId:String? = null, val dataSnapshot:DataSnapshot? = null
) : java.io.Serializable

data class Opportunity(
    val symbol:String,
    val companyName:String?,
    val price:Double,
    val dailyChangePct:Double?,
    val score:Int,
    val riskScore:Int,
    val direction:String,
    val technicalLabel:String,
    val volumeLabel:String,
    val kapLabel:String,
    val liquidityLabel:String,
    val support:Double?,
    val resistance:Double?,
    val source:String,
    val dataTimestamp:Long,
    val candles:List<Candle>,
    val technical:TechnicalSnapshot,
    val scoreBreakdown:List<String> = emptyList(),
    val dataConfidenceScore:Int = 100,
    val dataConfidenceLabel:String = "Yüksek",
    val finalSignalScore:Int = score,
    val volumeDirectionLabel:String = "Yön verisi yok",
    val isRealtime:Boolean = false,
    val delaySeconds:Int? = null,
    val currentSessionIncluded:Boolean = false,
    val exchangeTimestamp:Long = dataTimestamp,
    val receivedAt:Long = 0L,
    val receivedElapsedRealtime:Long = 0L,
    val signalGeneratedAt:Long = 0L,
    /** Local wall-clock receive time; REMOTE signalGeneratedAt remains the verified server generatedAt. */
    val clientReceivedAt:Long = 0L,
    val scanStartedAt:Long = 0L,
    val scanCompletedAt:Long = 0L,
    val dataMode:DataMode = DataMode.UNVERIFIED,
    val signalValidity:SignalValidity = SignalValidity.WATCH,
    val signalValidityReason:String = "Sinyal geçerliliği doğrulanmadı.",
    val scanRunId:String? = null,
    val snapshot:OpportunitySnapshot? = null,
    val longScore:Int = 0,
    val shortScore:Int = 0,
    /** Teknik eğilim puanları veri gecikmeli olsa bile ayrı tutulur; kesin işlem sinyali değildir. */
    val analysisLongScore:Int = longScore,
    val analysisShortScore:Int = shortScore,
    val analysisDirection:String = "NEUTRAL",
    val scoreExplanation:List<String> = emptyList(),
    val marketDataMetadata:MarketDataMetadata? = null,
    val decisionState:DecisionState = DecisionState.WATCH,
    val dataAgeMs:Long? = null,
    val rsiRiskMessage:String? = null,
    val setupType:String = "TEKNİK İZLEME",
    val qualityClass:String = "İZLE",
    val timingStatus:String = "DEĞERLENDİRİLMEDİ",
    val volumeAnomalyPct:Double? = null,
    val riskPlan:RiskPlan? = null,
    /** Canonical signal-engine score. Do not interpret as probability or ranking. */
    val signalScore:Int = finalSignalScore,
    /** Separate presentation/ranking score; not the signal-engine output. */
    val rankingScore:Int = finalSignalScore,
    /** Why ranking is or is not usable. Zero is never overloaded to mean multiple states. */
    val rankingStatus:RankingStatus = RankingStatus.CALCULATED,
    /** MTF context completeness is audit metadata, not a probability claim. */
    val mtfAvailableCount:Int = 0,
    val mtfExpectedCount:Int = 6,
    val mtfCompletenessPct:Int = 0,
    val mtfConsensusScore:Int? = null,
    val mtfConsensusLabel:String = "MTF VERİ YOK",
    val marketRegime:String = "VERİ YOK",
    val marketRegimeConfidence:Int = 0,
    val ensembleScore:Int? = null,
    val ensembleStatus:String = "FALLBACK_COMBINED",
    val strategyWeightsLabel:String = "Combined fallback",
    /** Teknik indikatörlerin üretildiği mum periyodu; tarama yenileme sıklığından bağımsızdır. */
    val analysisTimeframeMinutes:Int = 0,
    /** Otomatik tarama/yenileme periyodu; analiz timeframe'i değildir. */
    val scanCadenceMinutes:Int = 0,
    val scanMode:ScanMode = ScanMode.MANUAL,
    val calculationEngineVersion:String = "LEGACY",
    val calculationEngineMode:CalculationEngineMode = CalculationEngineMode.LEGACY,
    val signalAvailableWeight:Int = 0,
    val signalReasonCodes:List<String> = emptyList(),
    val signalConflictPenalty:Int = 0,
    // V5.3.5 REMOTE provenance/audit metadata. Defaults identify local/legacy records.
    val providerId:String? = null,
    val providerVersion:String? = null,
    val sourceType:String? = null,
    val snapshotId:String? = null,
    val requestId:String? = null,
    val calculationInputHash:String? = null,
    val calculationOutputHash:String? = null,
    val provenanceGeneratedAt:Long = 0L,
    val serverTime:Long = 0L,
    // Canonical REMOTE risk/input mirror retained after validation.
    val openInterestChangePct:Double? = null,
    val spreadPct:Double? = null,
    val liquidityScore:Int? = null,
    val slippageSensitivity:Double? = null,
    val structureDistanceAtr:Double? = null,
    val contractRiskPct:Int? = null
) : java.io.Serializable {
    @Deprecated("Use analysisTimeframeMinutes; scan cadence is a separate setting")
    val scanIntervalMinutes:Int get() = analysisTimeframeMinutes
}

data class ViopContract(
    val symbol:String,
    val underlying:String,
    val expiry:String,
    val contractType:ViopContractType = ViopContractType.FUTURE,
    val lastPrice:Double? = null,
    val bid:Double? = null,
    val ask:Double? = null,
    val dailyChangePct:Double? = null,
    val tickSize:Double? = null,
    val multiplier:Double? = null,
    val openInterest:Long? = null,
    val volume:Double? = null,
    val liquidity:String? = null,
    val rollover:String? = null,
    val providerId:String = "manual",
    val providerLabel:String = "Manuel",
    val isManual:Boolean = false,
    val currency:String? = null,
    val status:String = "Veri bekleniyor",
    val dataTimestamp:Long = 0L,
    val isRealtime:Boolean = false,
    val delaySeconds:Int? = null,
    val currentSessionIncluded:Boolean = false,
    val receivedAt:Long = 0L,
    val dataMode:DataMode = DataMode.UNVERIFIED,
    val validity:SignalValidity = SignalValidity.WATCH,
    val validityReason:String = "Sözleşme doğrulanmadı.",
    val lastTradingAt:Long? = null,
    val expiryAt:Long? = null,
    val exchangeTimezone:String? = null,
    val settlementType:String? = null,
    val marketDataMetadata:MarketDataMetadata? = null
) : java.io.Serializable { val exchangeTimestamp:Long get() = dataTimestamp }

data class ViopQuote(
    val symbol:String,
    val price:Double,
    val bid:Double?,
    val ask:Double?,
    val dailyChangePct:Double?,
    val volume:Double?,
    val openInterest:Long?,
    val exchangeTimestamp:Long,
    /** Device-side receive timestamp captured after the HTTP response reaches the phone. */
    val receivedAt:Long,
    /** Provider/server receive timestamp when supplied by the backend; never treated as device time. */
    val providerReceivedAt:Long? = null,
    val source:String,
    val realtime:Boolean,
    val delaySeconds:Int?,
    val currentSessionIncluded:Boolean,
    /** Provider-supplied point-in-time OI change. Never inferred from a single OI level. */
    val openInterestChangePct:Double? = null,
    /** As-of timestamp for openInterestChangePct; required whenever change is present. */
    val openInterestAsOfTimestamp:Long? = null,
    val marketDataMetadata:MarketDataMetadata? = null
) : java.io.Serializable

data class ViopOpportunity(
    val contract:ViopContract,
    val quote:ViopQuote,
    val candles:List<Candle>,
    val technical:TechnicalSnapshot,
    val technicalScore:Int,
    val riskScore:Int,
    val liquidityScore:Int,
    val expiryRisk:Int,
    val longScore:Int,
    val shortScore:Int,
    val finalScore:Int,
    val direction:String,
    val signalReason:String,
    val validity:SignalValidity,
    val dataAgeMs:Long,
    val historyCandleCount:Int,
    val decisionState:DecisionState = DecisionState.WATCH,
    val dataConfidenceScore:Int = 0,
    val riskPlan:RiskPlan? = null,
    val rankingScore:Int = finalScore,
    val rankingStatus:RankingStatus = RankingStatus.CALCULATED,
    val mtfAvailableCount:Int = 0,
    val mtfExpectedCount:Int = 6,
    val mtfCompletenessPct:Int = 0,
    val volumeAnomalyPct:Double? = null,
    val breakoutState:String = "YOK",
    val breakoutConfirmed:Boolean = false,
    val mtfConsensusScore:Int? = null,
    val mtfConsensusLabel:String = "MTF VERİ YOK",
    val marketRegime:String = "VERİ YOK",
    val marketRegimeConfidence:Int = 0,
    val ensembleScore:Int? = null,
    val ensembleStatus:String = "FALLBACK_COMBINED",
    val strategyWeightsLabel:String = "Combined fallback",
    /** Closed-bar price used by the decision engine; quote.price remains live display price. */
    val decisionPrice:Double = quote.price,
    val calculationEngineVersion:String = "LEGACY",
    val calculationEngineMode:CalculationEngineMode = CalculationEngineMode.LEGACY,
    val signalAvailableWeight:Int = 0,
    val signalReasonCodes:List<String> = emptyList(),
    val signalConflictPenalty:Int = 0,
    val analysisLongScore:Int = longScore,
    val analysisShortScore:Int = shortScore,
    val analysisDirection:String = "NEUTRAL",
    val scoreExplanation:List<String> = emptyList(),
    val marketDataMetadata:MarketDataMetadata? = quote.marketDataMetadata,
    /** Nihai yayın kararı. Production VİOP filtre/sayaçları yalnız bu alanı kullanır. */
    val publishedSignalDirection:ViopPublishedSignalDirection = ViopPublishedSignalDirection.WATCH,
    /** Analitik eğilim; yayınlanmış işlem sinyali değildir. */
    val analysisBias:ViopAnalysisBias = when (analysisDirection.uppercase()) {
        "LONG" -> ViopAnalysisBias.LONG
        "SHORT" -> ViopAnalysisBias.SHORT
        else -> ViopAnalysisBias.NEUTRAL
    }
) : java.io.Serializable

data class ViopUniversePage(
    val items:List<ViopContract>,
    val nextCursor:String? = null,
    val hasMore:Boolean = false,
    val totalCount:Int? = null,
    val universeAsOf:Long? = null,
    val pageNumber:Int = 1
)

data class ViopUniverseSnapshot(
    val contracts:List<ViopContract>,
    val providerTotal:Int?,
    val fetchedCount:Int,
    val fetchedUniqueCount:Int,
    val activeUniqueFutures:Int,
    val universeAsOf:Long?,
    val pageCount:Int,
    val completeness:ViopUniverseCompleteness,
    val completenessReason:String
)

data class ViopContractScanResult(
    val contract:ViopContract,
    val status:ViopContractOutcomeStatus,
    val opportunity:ViopOpportunity? = null,
    val errorCode:String? = null,
    val reason:String? = null,
    val quoteSuccess:Boolean = false,
    val historySuccess:Boolean = false,
    val analyzed:Boolean = false
) : java.io.Serializable

data class ViopScanError(val symbol:String, val code:String, val reason:String)

data class ViopScanProgress(
    val total:Int = 0,
    val quoteSuccess:Int = 0,
    val historySuccess:Int = 0,
    val analyzed:Int = 0,
    val insufficient:Int = 0,
    /** Legacy alias for rejected/eliminated rows. */
    val eliminated:Int = 0,
    /** Legacy alias for provider/no-data failures. */
    val failed:Int = 0,
    val rejected:Int = 0,
    val noData:Int = 0,
    val unsupportedOptions:Int = 0,
    val longCount:Int = 0,
    val shortCount:Int = 0,
    val watchCount:Int = 0,
    val publishedCount:Int = 0,
    val providerTotal:Int? = null,
    val fetchedCount:Int = 0,
    val fetchedUniqueCount:Int = 0,
    val activeUniqueFutures:Int = 0,
    val universeCompleteness:ViopUniverseCompleteness = ViopUniverseCompleteness.UNVERIFIED
) {
    val accountedTotal:Int get() = publishedCount + insufficient + rejected + noData + unsupportedOptions
}

data class ViopScanResult(
    val opportunities:List<ViopOpportunity>,
    val errors:List<ViopScanError>,
    val progress:ViopScanProgress,
    val startedAt:Long,
    val completedAt:Long,
    val status:ScanRunStatus,
    val scanId:String = "",
    val universeAsOf:Long = startedAt,
    val universeCompleteness:ViopUniverseCompleteness = ViopUniverseCompleteness.UNVERIFIED,
    val providerTotal:Int? = null,
    val fetchedCount:Int = 0,
    val fetchedUniqueCount:Int = 0,
    val activeUniqueFutures:Int = 0,
    val outcomes:List<ViopContractScanResult> = emptyList(),
    /** Newest market-data timestamp among published/analyzed rows. */
    val marketDataTimestamp:Long = opportunities.maxOfOrNull { it.quote.exchangeTimestamp } ?: 0L,
    /** Newest device receive timestamp among published/analyzed rows. */
    val deviceReceivedAt:Long = opportunities.maxOfOrNull { it.quote.receivedAt } ?: 0L
)


data class NewsItem(
    val id:String,
    val symbol:String?,
    val category:String,
    val title:String,
    val summary:String?,
    val source:String,
    val publishedAt:Long,
    val url:String?,
    val verified:Boolean = false,
    val receivedAt:Long? = null,
    val availableAt:Long? = null,
    val eventTime:Long? = null,
    val claimKey:String? = null,
    val eventKey:String? = null,
    val sourceType:String? = null,
    val originSourceId:String? = null,
    val supportsClaim:Boolean = true
)
