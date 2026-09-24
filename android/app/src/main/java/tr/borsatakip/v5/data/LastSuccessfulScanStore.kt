package tr.borsatakip.v5.data

import androidx.core.util.AtomicFile
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.CalculationEngineMode
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.ScanRun
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.model.ScanMode
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.TechnicalSnapshot
import java.io.File

/**
 * Son başarılı tarama yalnız COMPLETE olduğunda atomik olarak saklanır.
 * PARTIAL/FAILED/CANCELLED taramalar bu kaydı değiştiremez.
 */
class LastSuccessfulScanStore(context: Context) {
    private val file = File(context.filesDir, "last_successful_scan_v2.json")
    private val atomicFile = AtomicFile(file)

    suspend fun save(run: ScanRun, items: List<Opportunity>) = withContext(Dispatchers.IO) {
        require(run.status == ScanRunStatus.COMPLETE) { "Yalnız COMPLETE tarama son başarılı tarama olabilir." }
        val root = JSONObject().apply {
            put("scanRunId", run.scanRunId)
            put("scanStartedAt", run.scanStartedAt)
            put("scanCompletedAt", run.scanCompletedAt ?: 0L)
            put("providerId", run.providerId)
            put("status", run.status.name)
            put("count", run.count)
            put("errorCount", run.errorCount)
            put("items", JSONArray().apply { items.forEach { put(toJson(it)) } })
        }
        val out = atomicFile.startWrite()
        try { out.write(root.toString().toByteArray(Charsets.UTF_8)); atomicFile.finishWrite(out) }
        catch (t: Throwable) { atomicFile.failWrite(out); throw t }
    }

    suspend fun load(): Pair<ScanRun, List<Opportunity>>? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        runCatching {
            val root = JSONObject(String(atomicFile.readFully(), Charsets.UTF_8))
            val run = ScanRun(
                scanRunId = root.getString("scanRunId"),
                scanStartedAt = root.getLong("scanStartedAt"),
                scanCompletedAt = root.optLong("scanCompletedAt").takeIf { it > 0L },
                providerId = root.optString("providerId"),
                status = ScanRunStatus.valueOf(root.optString("status", ScanRunStatus.COMPLETE.name)),
                count = root.optInt("count"),
                errorCount = root.optInt("errorCount")
            )
            require(run.status == ScanRunStatus.COMPLETE)
            val a = root.optJSONArray("items") ?: JSONArray()
            val items = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::fromJson) }
            run to items
        }.getOrNull()
    }

    private fun toJson(x: Opportunity) = JSONObject().apply {
        put("symbol", x.symbol); put("companyName", x.companyName); put("price", x.price)
        putNullable("dailyChangePct", x.dailyChangePct); put("score", x.score); put("riskScore", x.riskScore)
        put("direction", x.direction); put("technicalLabel", x.technicalLabel); put("volumeLabel", x.volumeLabel)
        put("kapLabel", x.kapLabel); put("liquidityLabel", x.liquidityLabel)
        putNullable("support", x.support); putNullable("resistance", x.resistance)
        put("source", x.source); put("dataTimestamp", x.dataTimestamp)
        put("dataConfidenceScore", x.dataConfidenceScore); put("dataConfidenceLabel", x.dataConfidenceLabel)
        put("finalSignalScore", x.finalSignalScore); put("volumeDirectionLabel", x.volumeDirectionLabel)
        put("isRealtime", x.isRealtime); putNullable("delaySeconds", x.delaySeconds); put("currentSessionIncluded", x.currentSessionIncluded)
        put("exchangeTimestamp", x.exchangeTimestamp); put("receivedAt", x.receivedAt); put("clientReceivedAt", x.clientReceivedAt)
        put("scanStartedAt", x.scanStartedAt); put("scanCompletedAt", x.scanCompletedAt)
        put("dataMode", x.dataMode.name); put("signalValidity", x.signalValidity.name)
        put("signalValidityReason", x.signalValidityReason); put("scanRunId", x.scanRunId)
        put("technical", technicalJson(x.technical))
        putNullable("mtfConsensusScore", x.mtfConsensusScore); put("mtfConsensusLabel", x.mtfConsensusLabel)
        put("marketRegime", x.marketRegime); put("marketRegimeConfidence", x.marketRegimeConfidence)
        put("setupType", x.setupType); put("qualityClass", x.qualityClass); put("timingStatus", x.timingStatus)
        put("rankingScore", x.rankingScore)
        put("rankingStatus", x.rankingStatus.name)
        put("mtfAvailableCount", x.mtfAvailableCount)
        put("mtfExpectedCount", x.mtfExpectedCount)
        put("mtfCompletenessPct", x.mtfCompletenessPct)
        put("analysisTimeframeMinutes", x.analysisTimeframeMinutes)
        put("scanCadenceMinutes", x.scanCadenceMinutes)
        put("scanMode", x.scanMode.name)
        put("calculationEngineVersion", x.calculationEngineVersion)
        put("calculationEngineMode", x.calculationEngineMode.name)
        put("signalAvailableWeight", x.signalAvailableWeight)
        put("signalConflictPenalty", x.signalConflictPenalty)
        put("providerId", x.providerId ?: JSONObject.NULL)
        put("providerVersion", x.providerVersion ?: JSONObject.NULL)
        put("sourceType", x.sourceType ?: JSONObject.NULL)
        put("snapshotId", x.snapshotId ?: JSONObject.NULL)
        put("requestId", x.requestId ?: JSONObject.NULL)
        put("calculationInputHash", x.calculationInputHash ?: JSONObject.NULL)
        put("calculationOutputHash", x.calculationOutputHash ?: JSONObject.NULL)
        put("provenanceGeneratedAt", x.provenanceGeneratedAt)
        put("serverTime", x.serverTime)
        put("signalReasonCodes", JSONArray(x.signalReasonCodes))
        put("scoreBreakdown", JSONArray(x.scoreBreakdown))
        put("candles", JSONArray().apply { x.candles.takeLast(260).forEach { c ->
            put(JSONObject().apply { put("timestamp",c.timestamp); put("open",c.open); put("high",c.high); put("low",c.low); put("close",c.close); put("volume",c.volume) })
        } })
    }

    private fun fromJson(o: JSONObject): Opportunity {
        val t = o.optJSONObject("technical") ?: JSONObject()
        val technical = TechnicalSnapshot(
            d(t,"ema20"),d(t,"ema50"),d(t,"ema200"),d(t,"rsi14"),d(t,"macd"),d(t,"macdSignal"),
            d(t,"bbUpper"),d(t,"bbLower"),d(t,"atr14"),d(t,"vwap"),d(t,"volumeRatio"),d(t,"support"),d(t,"resistance"),d(t,"vwma"),d(t,"recommendation"),
            d(t,"vwma20"), d(t,"sessionVwap")
        )
        val ca = o.optJSONArray("candles") ?: JSONArray()
        val candles = (0 until ca.length()).mapNotNull { i -> ca.optJSONObject(i) }.mapNotNull { c ->
            val values = listOf(c.optDouble("open",Double.NaN),c.optDouble("high",Double.NaN),c.optDouble("low",Double.NaN),c.optDouble("close",Double.NaN),c.optDouble("volume",Double.NaN))
            if (c.optLong("timestamp") <= 0L || values.any { !it.isFinite() }) null else Candle(c.getLong("timestamp"),values[0],values[1],values[2],values[3],values[4])
        }
        val breakdownArray = o.optJSONArray("scoreBreakdown") ?: JSONArray()
        val breakdown = (0 until breakdownArray.length()).map { breakdownArray.optString(it) }
        return Opportunity(
            symbol=o.getString("symbol"), companyName=o.optString("companyName").takeIf { it.isNotBlank() && it != "null" },
            price=o.getDouble("price"), dailyChangePct=d(o,"dailyChangePct"), score=o.optInt("score"), riskScore=o.optInt("riskScore"),
            direction=o.optString("direction"), technicalLabel=o.optString("technicalLabel"), volumeLabel=o.optString("volumeLabel"),
            kapLabel=o.optString("kapLabel"), liquidityLabel=o.optString("liquidityLabel"), support=d(o,"support"), resistance=d(o,"resistance"),
            source=o.optString("source"), dataTimestamp=o.optLong("dataTimestamp"), candles=candles, technical=technical,
            scoreBreakdown=breakdown, dataConfidenceScore=o.optInt("dataConfidenceScore"), dataConfidenceLabel=o.optString("dataConfidenceLabel"),
            finalSignalScore=o.optInt("finalSignalScore"), volumeDirectionLabel=o.optString("volumeDirectionLabel"), isRealtime=o.optBoolean("isRealtime"),
            delaySeconds=i(o,"delaySeconds"), currentSessionIncluded=o.optBoolean("currentSessionIncluded"), exchangeTimestamp=o.optLong("exchangeTimestamp"),
            receivedAt=o.optLong("receivedAt"), clientReceivedAt=o.optLong("clientReceivedAt",0L), scanStartedAt=o.optLong("scanStartedAt"), scanCompletedAt=o.optLong("scanCompletedAt"),
            // Diskten geri yüklenen kayıt arşivdir; yeniden canlı bütünlük kontrolünden geçmeden REALTIME/VALID sayılmaz.
            dataMode=DataMode.UNVERIFIED,
            signalValidity=SignalValidity.WATCH,
            signalValidityReason="ARŞİV KAYDI • Canlı geçerlilik için yeniden veri doğrulaması gerekli. Üretim anındaki durum: ${o.optString("signalValidity")}/${o.optString("dataMode")}",
            scanRunId=o.optString("scanRunId").takeIf { it.isNotBlank() },
            setupType=o.optString("setupType","TEKNİK İZLEME"),
            qualityClass=o.optString("qualityClass","İZLE"),
            timingStatus=o.optString("timingStatus","DEĞERLENDİRİLMEDİ"),
            rankingScore=o.optInt("rankingScore", o.optInt("finalSignalScore")),
            rankingStatus=runCatching { tr.borsatakip.v5.model.RankingStatus.valueOf(o.optString("rankingStatus", tr.borsatakip.v5.model.RankingStatus.CALCULATED.name)) }.getOrDefault(tr.borsatakip.v5.model.RankingStatus.CALCULATED),
            mtfAvailableCount=o.optInt("mtfAvailableCount",0).coerceAtLeast(0),
            mtfExpectedCount=o.optInt("mtfExpectedCount",6).coerceAtLeast(0),
            mtfCompletenessPct=o.optInt("mtfCompletenessPct",0).coerceIn(0,100),
            mtfConsensusScore=i(o,"mtfConsensusScore"), mtfConsensusLabel=o.optString("mtfConsensusLabel","MTF VERİ YOK"),
            marketRegime=o.optString("marketRegime","VERİ YOK"), marketRegimeConfidence=o.optInt("marketRegimeConfidence",0),
            analysisTimeframeMinutes=o.optInt("analysisTimeframeMinutes",0).let { if (it == 0) 0 else ScanTimeframe.normalizeStoredMinutes(it) },
            scanCadenceMinutes=o.optInt("scanCadenceMinutes",0).coerceIn(0,1440),
            scanMode=runCatching { ScanMode.valueOf(o.optString("scanMode", ScanMode.MANUAL.name)) }.getOrDefault(ScanMode.MANUAL),
            calculationEngineVersion=o.optString("calculationEngineVersion", "LEGACY"),
            calculationEngineMode=runCatching { CalculationEngineMode.valueOf(o.optString("calculationEngineMode", CalculationEngineMode.LEGACY.name)) }.getOrDefault(CalculationEngineMode.LEGACY),
            signalAvailableWeight=o.optInt("signalAvailableWeight",0).coerceIn(0,100),
            signalReasonCodes=(o.optJSONArray("signalReasonCodes") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } },
            signalConflictPenalty=o.optInt("signalConflictPenalty",0).coerceIn(0,100),
            providerId=o.optString("providerId").takeIf { it.isNotBlank() && it != "null" },
            providerVersion=o.optString("providerVersion").takeIf { it.isNotBlank() && it != "null" },
            sourceType=o.optString("sourceType").takeIf { it.isNotBlank() && it != "null" },
            snapshotId=o.optString("snapshotId").takeIf { it.isNotBlank() && it != "null" },
            requestId=o.optString("requestId").takeIf { it.isNotBlank() && it != "null" },
            calculationInputHash=o.optString("calculationInputHash").takeIf { it.isNotBlank() && it != "null" },
            calculationOutputHash=o.optString("calculationOutputHash").takeIf { it.isNotBlank() && it != "null" },
            provenanceGeneratedAt=o.optLong("provenanceGeneratedAt",0L),
            serverTime=o.optLong("serverTime",0L)
        )
    }

    private fun technicalJson(t: TechnicalSnapshot) = JSONObject().apply {
        putNullable("ema20",t.ema20); putNullable("ema50",t.ema50); putNullable("ema200",t.ema200); putNullable("rsi14",t.rsi14)
        putNullable("macd",t.macd); putNullable("macdSignal",t.macdSignal); putNullable("bbUpper",t.bbUpper); putNullable("bbLower",t.bbLower)
        putNullable("atr14",t.atr14); putNullable("vwap",t.vwap); putNullable("volumeRatio",t.volumeRatio); putNullable("support",t.support)
        putNullable("resistance",t.resistance); putNullable("vwma",t.vwma); putNullable("recommendation",t.recommendation)
        putNullable("vwma20",t.vwma20); putNullable("sessionVwap",t.sessionVwap)
    }
    private fun JSONObject.putNullable(k:String,v:Any?) { if (v == null) put(k,JSONObject.NULL) else put(k,v) }
    private fun d(o:JSONObject,k:String):Double? = if (o.has(k) && !o.isNull(k)) o.optDouble(k,Double.NaN).takeIf { it.isFinite() } else null
    private fun i(o:JSONObject,k:String):Int? = if (o.has(k) && !o.isNull(k)) o.optInt(k) else null
}
