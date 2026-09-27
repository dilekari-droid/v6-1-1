package tr.borsatakip.v5.data

import android.content.Context
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.ScanRun
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.RiskPlan
import tr.borsatakip.v5.analysis.TradeForwardEngine
import tr.borsatakip.v5.analysis.StrategyProfileEngine
import tr.borsatakip.v5.analysis.StrategyComparisonMath
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

enum class HistoryLoadState { NO_HISTORY, PARTIAL_HISTORY, COMPLETE_HISTORY, HISTORY_LOAD_ERROR }

data class HistoryOverview(
    val state: HistoryLoadState,
    val totalRecords: Int,
    val latestScanId: String? = null,
    val latestScanStatus: ScanRunStatus? = null
)

data class SignalHistoryFilter(
    val direction: String? = null,
    val minScore: Int? = null,
    val symbol: String? = null,
    val fromTime: Long? = null,
    val toTime: Long? = null
)



data class StrategyPerformance(
    val strategyId: String, val strategyLabel: String, val totalVerified: Int,
    val positive: Int, val negative: Int, val successRatePct: Double?,
    val averageReturnPct: Double?, val profitFactor: Double?, val maxDrawdownPct: Double?,
    val target1Count: Int, val target2Count: Int, val stopCount: Int,
    val netPnl: Double?, val netPnlSampleCount: Int,
    val confidenceLabel: String, val sampleSufficient: Boolean
)

data class ForwardStats(
    val horizon: String,
    val totalVerified: Int,
    val positive: Int,
    val negative: Int,
    val successRatePct: Double?,
    val averageReturnPct: Double?,
    val medianReturnPct: Double?,
    val averageGainPct: Double?,
    val averageLossPct: Double?,
    val profitFactor: Double?,
    val curve: List<Double>
)

/**
 * Sinyal geçmişi tek-yazar Mutex ile korunur. Forward sonucu güncel quote üzerinden değil,
 * signalGeneratedAt + horizon hedef zamanından sonraki ilk doğrulanmış tarihsel piyasa gözlemiyle yazılır.
 */
class SignalHistoryStore(context: Context) {
    private val settings = SettingsStore(context)
    private val file = File(context.filesDir, "signal_history_v1.json")

    suspend fun recordScan(run: ScanRun, items: List<Opportunity>): Int = withContext(Dispatchers.IO) {
        if (!SignalHistoryPolicy.shouldPersist(run.status, items.size)) return@withContext 0
        GLOBAL_MUTEX.withLock {
            val root = readRootUnsafe()
            val records = root.optJSONArray("records") ?: JSONArray()
            val existing = HashSet<String>()
            for (i in 0 until records.length()) existing += records.optJSONObject(i)?.optString("id").orEmpty()

            val now = System.currentTimeMillis()
            var inserted = 0
            items.asSequence()
                .filter { it.signalValidity == SignalValidity.VALID || it.signalValidity == SignalValidity.WATCH }
                .filter { it.price.isFinite() && it.price > 0.0 }
                .forEach { x ->
                    val generatedAt = x.signalGeneratedAt.takeIf { it > 0L }
                    if (generatedAt == null) return@forEach
                    val id = SignalHistoryPolicy.uniqueKey(run.scanRunId, x.symbol, generatedAt)
                    if (id !in existing) {
                        records.put(JSONObject().apply {
                            put("id", id)
                            put("scanRunId", run.scanRunId)
                            put("scanStatus", run.status.name)
                            put("symbol", x.symbol)
                            put("companyName", x.companyName ?: "")
                            put("direction", x.direction)
                            put("signalPrice", x.price)
                            put("finalSignalScore", x.finalSignalScore)
                            put("longScore", x.longScore)
                            put("shortScore", x.shortScore)
                            put("rankingScore", x.rankingScore)
                            put("rankingStatus", x.rankingStatus.name)
                            put("mtfAvailableCount", x.mtfAvailableCount)
                            put("mtfExpectedCount", x.mtfExpectedCount)
                            put("mtfCompletenessPct", x.mtfCompletenessPct)
                            put("ensembleScore", x.ensembleScore ?: JSONObject.NULL)
                            put("ensembleStatus", x.ensembleStatus)
                            put("strategyWeightsLabel", x.strategyWeightsLabel)
                            val strategyScores = StrategyProfileEngine.evaluate(x)
                            put("strategyIds", JSONArray().apply { strategyScores.filter { it.active }.forEach { put(it.id.name) } })
                            put("strategyScores", JSONObject().apply { strategyScores.forEach { put(it.id.name, it.score) } })
                            put("mtfConsensusScore", x.mtfConsensusScore ?: JSONObject.NULL)
                            put("mtfConsensusLabel", x.mtfConsensusLabel)
                            put("marketRegime", x.marketRegime)
                            put("marketRegimeConfidence", x.marketRegimeConfidence)
                            put("technicalScore", x.score)
                            put("riskScore", x.riskScore)
                            put("dataConfidenceScore", x.dataConfidenceScore)
                            put("calculationEngineVersion", x.calculationEngineVersion)
                            put("calculationEngineMode", x.calculationEngineMode.name)
                            put("signalAvailableWeight", x.signalAvailableWeight)
                            put("signalConflictPenalty", x.signalConflictPenalty)
                            put("signalReasonCodes", JSONArray(x.signalReasonCodes))
                            put("decisionState", x.decisionState.name)
                            put("setupType", x.setupType)
                            put("volumeAnomalyPct", x.volumeAnomalyPct ?: JSONObject.NULL)
                            put("rsiRiskMessage", x.rsiRiskMessage ?: "")
                            x.riskPlan?.let { p ->
                                put("riskPlan", JSONObject().apply {
                                    put("entry", p.entry); put("stop", p.stop ?: JSONObject.NULL)
                                    put("target1", p.target1 ?: JSONObject.NULL); put("target2", p.target2 ?: JSONObject.NULL)
                                    put("rr1", p.rr1 ?: JSONObject.NULL); put("rr2", p.rr2 ?: JSONObject.NULL)
                                })
                            }
                            put("signalValidity", x.signalValidity.name)
                            put("dataMode", x.dataMode.name)
                            put("source", x.source)
                            put("providerId", x.providerId ?: JSONObject.NULL)
                            put("providerVersion", x.providerVersion ?: JSONObject.NULL)
                            put("sourceType", x.sourceType ?: JSONObject.NULL)
                            put("snapshotId", x.snapshotId ?: JSONObject.NULL)
                            put("requestId", x.requestId ?: JSONObject.NULL)
                            put("calculationInputHash", x.calculationInputHash ?: JSONObject.NULL)
                            put("calculationOutputHash", x.calculationOutputHash ?: JSONObject.NULL)
                            put("provenanceGeneratedAt", x.provenanceGeneratedAt)
                            put("serverTime", x.serverTime)
                            put("signalReason", x.signalValidityReason)
                            put("signalGeneratedAt", generatedAt)
                            put("signalTime", generatedAt) // backward UI compatibility
                            put("marketDataTime", x.exchangeTimestamp)
                            put("receivedAt", x.receivedAt)
                            put("clientReceivedAt", x.clientReceivedAt)
                            put("dataAge", if (x.exchangeTimestamp > 0L) (generatedAt - x.exchangeTimestamp).coerceAtLeast(0L) else -1L)
                            put("scanCompletedAt", run.scanCompletedAt ?: 0L)
                            put("scanVersion", BuildConfig.VERSION_NAME)
                            put("createdAt", now)
                            put("exchangeTimestamp", x.exchangeTimestamp)
                            put("recordedAt", generatedAt)
                            put("outcomes", JSONObject())
                        })
                        existing += id
                        inserted++
                    }
                }

            root.put("records", trim(records, 3000))
            writeRootUnsafe(root)
            inserted
        }
    }

    suspend fun recordCompleteScan(run: ScanRun, items: List<Opportunity>): Int = recordScan(run, items)

    suspend fun overview(): HistoryOverview = withContext(Dispatchers.IO) {
        GLOBAL_MUTEX.withLock {
            try {
                if (!file.isFile) return@withLock HistoryOverview(HistoryLoadState.NO_HISTORY, 0)
                val root = readRootUnsafe()
                val records = root.optJSONArray("records") ?: JSONArray()
                if (records.length() == 0) return@withLock HistoryOverview(HistoryLoadState.NO_HISTORY, 0)
                val latest = records.optJSONObject(records.length() - 1)
                val latestStatus = runCatching { ScanRunStatus.valueOf(latest?.optString("scanStatus").orEmpty()) }.getOrNull()
                val state = when (latestStatus) {
                    ScanRunStatus.PARTIAL -> HistoryLoadState.PARTIAL_HISTORY
                    ScanRunStatus.COMPLETE -> HistoryLoadState.COMPLETE_HISTORY
                    else -> HistoryLoadState.COMPLETE_HISTORY
                }
                HistoryOverview(state, records.length(), latest?.optString("scanRunId")?.takeIf { it.isNotBlank() }, latestStatus)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                HistoryOverview(HistoryLoadState.HISTORY_LOAD_ERROR, 0)
            }
        }
    }

    /**
     * Açık politika:
     * - 15m/30m/1h hedefleri için hedef sonrası en fazla 30 dk,
     * - 4h hedefi için en fazla 90 dk,
     * - 1d hedefi için hafta sonu/kapalı seans köprüsü amacıyla en fazla 72 saat tolerans.
     * Tolerans içinde gerçek mum yoksa outcome yazılmaz; güncel quote ile tahmin yapılmaz.
     */
    suspend fun updateDueOutcomes(provider: MarketDataProvider, maxSignals: Int = 20) = withContext(Dispatchers.IO) {
        data class DueWork(val recordId:String,val symbol:String,val generatedAt:Long,val signalPrice:Double,val direction:String,val due:Map<String,Long>,val minTarget:Long,val maxTarget:Long,val riskPlan:RiskPlan?)
        val now = System.currentTimeMillis()
        val work = GLOBAL_MUTEX.withLock {
            val records = readRootUnsafe().optJSONArray("records") ?: return@withLock emptyList<DueWork>()
            val list=mutableListOf<DueWork>()
            for (i in records.length()-1 downTo 0) {
                if (list.size >= maxSignals) break
                val r=records.optJSONObject(i) ?: continue
                val generatedAt=r.optLong("signalGeneratedAt",0L)
                if (generatedAt<=0L) continue
                val outcomes=r.optJSONObject("outcomes")
                val already=ForwardOutcomeMatcher.horizons.keys.filter { outcomes?.has(it)==true }.toSet()
                val due=ForwardOutcomeMatcher.dueHorizons(generatedAt,now,already)
                if (due.isEmpty()) continue
                val signalPrice=r.optDouble("signalPrice",Double.NaN)
                if (!signalPrice.isFinite() || signalPrice<=0.0) continue
                val rp = r.optJSONObject("riskPlan")?.let { p ->
                    RiskPlan(
                        entry=p.optDouble("entry",Double.NaN).takeIf { it.isFinite() && it>0.0 } ?: signalPrice,
                        stop=p.optDouble("stop",Double.NaN).takeIf { it.isFinite() },
                        target1=p.optDouble("target1",Double.NaN).takeIf { it.isFinite() },
                        target2=p.optDouble("target2",Double.NaN).takeIf { it.isFinite() },
                        rr1=p.optDouble("rr1",Double.NaN).takeIf { it.isFinite() },
                        rr2=p.optDouble("rr2",Double.NaN).takeIf { it.isFinite() }
                    )
                }
                list += DueWork(
                    r.optString("id"), r.optString("symbol"), generatedAt, signalPrice, r.optString("direction"), due,
                    due.minOf { ForwardOutcomeMatcher.tradingTargetTime(generatedAt, it.value) },
                    due.maxOf { ForwardOutcomeMatcher.tradingTargetTime(generatedAt, it.value) + ForwardOutcomeMatcher.toleranceMs(it.key) },
                    rp
                )
            }
            list
        }
        data class OutcomeUpdate(val id:String,val outcomes:JSONObject,val status:String,val tradeOutcome:JSONObject?=null)
        val updates=mutableListOf<OutcomeUpdate>()
        for (item in work) {
            val history=try { withTimeout(FORWARD_FETCH_TIMEOUT_MS) { provider.fetchHistory(item.symbol,item.generatedAt,item.maxTarget,1) } }
            catch (ce: CancellationException) { throw ce }
            catch (pe:ProviderException) { updates += OutcomeUpdate(item.recordId,JSONObject(),"PROVIDER_${pe.code.name}"); continue }
            catch (_:Throwable) { updates += OutcomeUpdate(item.recordId,JSONObject(),"HISTORY_ERROR"); continue }
            if (history.isEmpty()) { updates += OutcomeUpdate(item.recordId,JSONObject(),"HISTORY_UNAVAILABLE"); continue }
            val outcomes=JSONObject(); var wrote=false
            for ((name,horizonMs) in item.due) {
                val target=ForwardOutcomeMatcher.tradingTargetTime(item.generatedAt,horizonMs); val matched=ForwardOutcomeMatcher.match(history,target,ForwardOutcomeMatcher.toleranceMs(name)) ?: continue
                val price=matched.close.takeIf { it.isFinite()&&it>0.0 } ?: continue
                val ret=ForwardOutcomeMatcher.directionalReturnPct(item.signalPrice,price,item.direction) ?: continue
                outcomes.put(name,JSONObject().apply { put("targetHorizonMs",horizonMs); put("targetTime",target); put("matchedMarketTime",matched.timestamp); put("lagMs",matched.timestamp-target); put("price",price); put("directionalReturnPct",ret); put("source",provider.displayName); put("verification","VERIFIED_HISTORICAL"); put("calculatedAt",now) }); wrote=true
            }
            val tradeCosts = settings.tradeSimulationQuantity.takeIf { it > 0.0 }?.let { qty ->
                TradeForwardEngine.CostConfig(qty, 1.0, settings.tradeCommissionPerSide, settings.tradeSlippageBpsPerSide)
            }
            val trade = TradeForwardEngine.evaluate(item.direction,item.generatedAt,item.riskPlan,history,tradeCosts)
            val tradeJson = if (trade.event != TradeForwardEngine.Event.NO_PLAN) JSONObject().apply {
                put("event",trade.event.name); put("eventTime",trade.eventTime ?: JSONObject.NULL); put("eventPrice",trade.eventPrice ?: JSONObject.NULL)
                put("grossReturnPct",trade.grossReturnPct ?: JSONObject.NULL); put("candlesChecked",trade.candlesChecked); put("reason",trade.reason)
                put("grossPnl",trade.grossPnl ?: JSONObject.NULL); put("totalCosts",trade.totalCosts ?: JSONObject.NULL); put("netPnl",trade.netPnl ?: JSONObject.NULL); put("netReturnPct",trade.netReturnPct ?: JSONObject.NULL); put("target1Time",trade.target1Time ?: JSONObject.NULL)
                put("mfePct",trade.mfePct ?: JSONObject.NULL); put("maePct",trade.maePct ?: JSONObject.NULL)
                put("verification","VERIFIED_HISTORICAL"); put("costModel",if(tradeCosts==null)"NOT_CONFIGURED_GROSS_ONLY" else "CONFIGURED_NET_PNL"); put("calculatedAt",now)
            } else null
            updates += OutcomeUpdate(item.recordId,outcomes,if(wrote) "VERIFIED_PARTIAL_OR_COMPLETE" else "NO_MATCH_WITHIN_TOLERANCE",tradeJson)
        }
        if (updates.isNotEmpty()) GLOBAL_MUTEX.withLock {
            val root=readRootUnsafe(); val records=root.optJSONArray("records") ?: return@withLock; var changed=false
            for (u in updates) for (i in 0 until records.length()) {
                val r=records.optJSONObject(i) ?: continue; if (r.optString("id")!=u.id) continue
                if (u.outcomes.length()>0) { val merged=r.optJSONObject("outcomes") ?: JSONObject().also { r.put("outcomes",it) }; val keys=u.outcomes.keys(); while(keys.hasNext()){ val k=keys.next(); merged.put(k,u.outcomes.get(k)) } }
                if (u.tradeOutcome != null) r.put("tradeOutcome",u.tradeOutcome)
                r.put("forwardStatus",u.status); changed=true; break
            }
            if(changed) writeRootUnsafe(root)
        }
    }

    suspend fun forwardStats(horizon: String): ForwardStats = withContext(Dispatchers.IO) {
        GLOBAL_MUTEX.withLock {
            val records = readRootUnsafe().optJSONArray("records") ?: JSONArray()
            val values = mutableListOf<Double>()
            for (i in 0 until records.length()) {
                val o = records.optJSONObject(i)?.optJSONObject("outcomes")?.optJSONObject(horizon) ?: continue
                if (o.optString("verification") != "VERIFIED_HISTORICAL") continue
                val v = o.optDouble("directionalReturnPct", Double.NaN)
                if (v.isFinite()) values += v
            }
            val sorted = values.sorted()
            val median = when {
                sorted.isEmpty() -> null
                sorted.size % 2 == 1 -> sorted[sorted.size / 2]
                else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            }
            var cumulative = 0.0
            val curve = values.map { cumulative += it; cumulative }
            val positiveValues = values.filter { it > 0.0 }
            val negativeValues = values.filter { it < 0.0 }
            val positive = positiveValues.size
            val negative = negativeValues.size
            val grossProfit = positiveValues.sum()
            val grossLoss = -negativeValues.sum()
            val profitFactor = when {
                values.isEmpty() -> null
                grossLoss > 0.0 -> grossProfit / grossLoss
                grossProfit > 0.0 -> Double.POSITIVE_INFINITY
                else -> null
            }
            ForwardStats(
                horizon = horizon,
                totalVerified = values.size,
                positive = positive,
                negative = negative,
                successRatePct = if (values.isEmpty()) null else positive * 100.0 / values.size,
                averageReturnPct = values.takeIf { it.isNotEmpty() }?.average(),
                medianReturnPct = median,
                averageGainPct = positiveValues.takeIf { it.isNotEmpty() }?.average(),
                averageLossPct = negativeValues.takeIf { it.isNotEmpty() }?.average(),
                profitFactor = profitFactor,
                curve = curve
            )
        }
    }

    suspend fun strategyPerformance(
        horizon: String,
        direction: String? = null,
        regime: String? = null,
        limit: Int = Int.MAX_VALUE
    ): List<StrategyPerformance> = withContext(Dispatchers.IO) {
        GLOBAL_MUTEX.withLock {
            val records = readRootUnsafe().optJSONArray("records") ?: JSONArray()
            val returns = linkedMapOf<String, MutableList<Double>>()
            val netPnls = linkedMapOf<String, MutableList<Double>>()
            val t1 = linkedMapOf<String, Int>(); val t2 = linkedMapOf<String, Int>(); val stops = linkedMapOf<String, Int>()
            StrategyProfileEngine.StrategyId.entries.forEach { id ->
                returns[id.name] = mutableListOf(); netPnls[id.name] = mutableListOf(); t1[id.name] = 0; t2[id.name] = 0; stops[id.name] = 0
            }
            var acceptedRecords = 0
            for (i in records.length() - 1 downTo 0) {
                if (acceptedRecords >= limit) break
                val r = records.optJSONObject(i) ?: continue
                if (r.optString("decisionState") != "VERIFIED_OPPORTUNITY") continue
                if (direction != null && !r.optString("direction").equals(direction, true)) continue
                if (regime != null && !r.optString("marketRegime", "VERİ YOK").equals(regime, true)) continue
                val ids = r.optJSONArray("strategyIds") ?: continue
                val outcome = r.optJSONObject("outcomes")?.optJSONObject(horizon) ?: continue
                if (outcome.optString("verification") != "VERIFIED_HISTORICAL") continue
                val ret = outcome.optDouble("directionalReturnPct", Double.NaN)
                if (!ret.isFinite()) continue
                acceptedRecords++
                val trade = r.optJSONObject("tradeOutcome")
                val event = trade?.optString("event").orEmpty()
                val net = trade?.optDouble("netPnl", Double.NaN)?.takeIf { it.isFinite() }
                for (j in 0 until ids.length()) {
                    val id = ids.optString(j); val list = returns[id] ?: continue
                    list += ret; if (net != null) netPnls[id]?.add(net)
                    when (event) {
                        "TARGET2" -> t2[id] = (t2[id] ?: 0) + 1
                        "TARGET1" -> t1[id] = (t1[id] ?: 0) + 1
                        "TARGET1_THEN_STOP" -> { t1[id] = (t1[id] ?: 0) + 1; stops[id] = (stops[id] ?: 0) + 1 }
                        "STOP" -> stops[id] = (stops[id] ?: 0) + 1
                    }
                }
            }
            StrategyProfileEngine.StrategyId.entries.map { id ->
                val vals = returns[id.name].orEmpty(); val positives = vals.count { it > 0.0 }; val negatives = vals.count { it < 0.0 }; val nets = netPnls[id.name].orEmpty()
                StrategyPerformance(
                    id.name, id.label, vals.size, positives, negatives,
                    vals.takeIf { it.isNotEmpty() }?.let { positives * 100.0 / it.size },
                    vals.takeIf { it.isNotEmpty() }?.average(),
                    StrategyComparisonMath.profitFactor(vals), StrategyComparisonMath.maxDrawdownPct(vals),
                    t1[id.name] ?: 0, t2[id.name] ?: 0, stops[id.name] ?: 0,
                    nets.takeIf { it.isNotEmpty() }?.sum(), nets.size, StrategyComparisonMath.confidenceLabel(vals.size), vals.size >= 20
                )
            }
        }
    }

    suspend fun summaryLines(limit: Int = 200, filter: SignalHistoryFilter = SignalHistoryFilter()): List<String> = withContext(Dispatchers.IO) {
        GLOBAL_MUTEX.withLock {
            val a = readRootUnsafe().optJSONArray("records") ?: return@withLock emptyList()
            val df = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr", "TR"))
            buildList {
                for (i in a.length() - 1 downTo 0) {
                    if (size >= limit) break
                    val r = a.optJSONObject(i) ?: continue
                    val direction = r.optString("direction")
                    val score = r.optInt("finalSignalScore")
                    val symbol = r.optString("symbol")
                    val signalTime = r.optLong("signalGeneratedAt", 0L)
                    if (filter.direction != null && !direction.equals(filter.direction, true)) continue
                    if (filter.minScore != null && score < filter.minScore) continue
                    if (!filter.symbol.isNullOrBlank() && !symbol.contains(filter.symbol, true)) continue
                    if (filter.fromTime != null && signalTime < filter.fromTime) continue
                    if (filter.toTime != null && signalTime > filter.toTime) continue

                    val outcomes = r.optJSONObject("outcomes") ?: JSONObject()
                    val outcomeText = buildList {
                        for (name in ForwardOutcomeMatcher.horizons.keys) {
                            val o = outcomes.optJSONObject(name) ?: continue
                            val pct = o.optDouble("directionalReturnPct", Double.NaN)
                            val matched = o.optLong("matchedMarketTime", 0L)
                            val lag = o.optLong("lagMs", -1L)
                            if (pct.isFinite()) add("$name ${"%+.2f".format(pct)}% • piyasa ${if (matched > 0) df.format(Date(matched)) else "?"} • gecikme ${if (lag >= 0) formatLag(lag) else "?"}")
                        }
                    }.joinToString(" • ").ifBlank {
                        when (r.optString("forwardStatus")) {
                            LEGACY_UNVERIFIABLE -> "Forward doğrulanamaz: legacy kayıtta signalGeneratedAt yok"
                            else -> "Forward outcome henüz doğrulanmadı"
                        }
                    }
                    val scanStatus = r.optString("scanStatus").ifBlank { "LEGACY" }
                    val longScore = r.optInt("longScore", -1)
                    val shortScore = r.optInt("shortScore", -1)
                    val ranking = r.optInt("rankingScore", score)
                    val decision = r.optString("decisionState").ifBlank { r.optString("signalValidity") }
                    val sideScores = if (longScore >= 0 && shortScore >= 0) " • LONG $longScore • SHORT $shortScore" else ""
                    val strategyIds = r.optJSONArray("strategyIds")
                    val strategyText = if (strategyIds != null && strategyIds.length() > 0) buildList { for (j in 0 until strategyIds.length()) add(strategyIds.optString(j)) }.joinToString(",") else "LEGACY/ÖLÇÜLMEDİ"
                    val trade = r.optJSONObject("tradeOutcome")
                    val tradeText = trade?.let { " • Trade ${it.optString("event")} ${it.optDouble("grossReturnPct",Double.NaN).takeIf { v -> v.isFinite() }?.let { v -> "%+.2f%%".format(v) } ?: ""}" }.orEmpty()
                    add("$symbol $direction • Nihai $score/100 • Sıralama $ranking/100$sideScores • Teknik ${r.optInt("technicalScore")}/100 • Risk ${r.optInt("riskScore")}/100\nKarar: $decision • Tarama: $scanStatus • Strateji: $strategyText • Sinyal: ${if (signalTime > 0) df.format(Date(signalTime)) else "DOĞRULANAMADI"} • Fiyat ${"%.2f".format(r.optDouble("signalPrice"))}\nVeri modu ${r.optString("dataMode")} • Kaynak ${r.optString("source")} • Veri zamanı ${r.optLong("marketDataTime", r.optLong("exchangeTimestamp", 0L))}\n$outcomeText$tradeText")
                }
            }
        }
    }

    private fun readRootUnsafe(): JSONObject {
        if (!file.isFile) return JSONObject()
        val bytes = file.readBytes()
        return try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val quarantine = File(file.parentFile, "${file.name}.corrupt.${System.currentTimeMillis()}")
            runCatching { quarantine.writeBytes(bytes) }
            throw IllegalStateException("Sinyal geçmişi bozuk; özgün içerik karantinaya alındı: ${quarantine.name}", t)
        }
    }

    private fun writeRootUnsafe(root: JSONObject) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(root.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (t: Throwable) {
            atomic.failWrite(out)
            throw t
        }
    }

    private fun trim(a: JSONArray, max: Int): JSONArray {
        val start = (a.length() - max).coerceAtLeast(0)
        val out = JSONArray()
        for (i in start until a.length()) out.put(a.get(i))
        return out
    }


    private fun formatLag(ms: Long): String = when {
        abs(ms) < 60_000L -> "${ms / 1000}s"
        abs(ms) < 3_600_000L -> "${ms / 60_000}dk"
        else -> "${ms / 3_600_000}sa"
    }

    companion object {
        // Aynı process içindeki bütün SignalHistoryStore örnekleri aynı dosya için tek read-modify-write kilidini paylaşır.
        private val GLOBAL_MUTEX = Mutex()
        private const val LEGACY_UNVERIFIABLE = "UNVERIFIABLE_LEGACY_TIME"
        private const val FORWARD_FETCH_TIMEOUT_MS = 25_000L
    }
}
