package tr.borsatakip.v5.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import tr.borsatakip.v5.analysis.v531.RemoteEngineContract
import tr.borsatakip.v5.analysis.v531.RemoteEngineMetadata
import tr.borsatakip.v5.analysis.v531.ScoringConfig
import tr.borsatakip.v5.analysis.v533.V533SnapshotInvariant
import tr.borsatakip.v5.analysis.v533.V533SnapshotItemRef
import tr.borsatakip.v5.analysis.v533.V533SnapshotMetadata
import tr.borsatakip.v5.analysis.v535.V535MtfSemantics
import tr.borsatakip.v5.analysis.v539.V539RiskRewardGate
import tr.borsatakip.v5.analysis.v540.V540FreshnessPolicy
import tr.borsatakip.v5.analysis.v540.V540CalculationOutputSchema
import tr.borsatakip.v5.analysis.v540.V540RankingEngine
import tr.borsatakip.v5.analysis.v535.V535RemoteCalculationInput
import tr.borsatakip.v5.analysis.v535.V535RemoteInputConsistency
import tr.borsatakip.v5.analysis.v535.V535RemoteInputHash
import tr.borsatakip.v5.analysis.v535.V535RemoteItemMirror
import tr.borsatakip.v5.analysis.v536.V536ClockEvidence
import tr.borsatakip.v5.analysis.v536.V536ClockIntegrity
import tr.borsatakip.v5.analysis.v536.V536ClockPolicy
import tr.borsatakip.v5.analysis.v538.V538AttestationEnvelope
import tr.borsatakip.v5.analysis.v538.V538AttestationStatus
import tr.borsatakip.v5.analysis.v538.V538ReplayToken
import tr.borsatakip.v5.analysis.v538.V538ServerAttestationVerifier
import tr.borsatakip.v5.analysis.v538.V538TrustedKeyRegistry
import tr.borsatakip.v5.analysis.v538.V538TrustedTimeKey
import tr.borsatakip.v5.analysis.v538.V538TrustedTimeRegistry
import tr.borsatakip.v5.data.v538.V538ReplayStore
import tr.borsatakip.v5.data.v538.V538ObservabilityStore
import tr.borsatakip.v5.analysis.v536.V536RemoteEvent
import tr.borsatakip.v5.analysis.v536.V536RemoteEventType
import tr.borsatakip.v5.analysis.v536.V536RemoteObservability
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.model.CalculationEngineMode
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.ScanMode
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.TechnicalSnapshot
import java.util.concurrent.TimeUnit
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Realtime scanner REST client.
 *
 * analysisTimeframeMinutes, scanCadenceMinutes and scanMode are separate contract fields.
 * TradingView alarms are never accepted as market data.
 */
class RealtimeScannerClient(context: Context) {
    data class Snapshot(
        val provider: String,
        val providerId: String,
        val providerVersion: String,
        val sourceType: String,
        val snapshotId: String,
        val requestId: String,
        val serverTime: Long,
        val snapshotHash: String,
        val generatedAt: Long,
        val serverEpoch: String,
        val serverEpochCreatedAt: Long,
        val serverSequence: Long,
        val replayNonce: String,
        val replayIssuedAt: Long,
        val replayExpiresAt: Long,
        val attestationKeyId: String,
        val attestationKeyGeneration: Long,
        val attestationStatus: V538AttestationStatus,
        val trustedNowMillis: Long,
        val trackedSymbols: Int,
        val freshSymbols: Int,
        val readySymbols: Int,
        val resultItemCount: Int,
        val universeVerified: Boolean,
        val universeSymbols: Int,
        val freshCoveragePct: Double?,
        val readyCoveragePct: Double?,
        val minReadyBars: Int,
        val engineVersion: Long,
        val calculationEngineVersion: String,
        val calculationEngineMode: CalculationEngineMode,
        val analysisTimeframeMinutes: Int,
        val scanCadenceMinutes: Int,
        val scanMode: ScanMode,
        val opportunities: List<Opportunity>
    ) {
        val expectedSymbols: Int get() = if (universeVerified) universeSymbols else 0
        @Deprecated("Use analysisTimeframeMinutes")
        val intervalMinutes: Int get() = analysisTimeframeMinutes
    }

    private val settings = SettingsStore(context)
    private val replayStore = V538ReplayStore(context)
    private val attestationKeys = V538TrustedKeyRegistry.parse(BuildConfig.ATTESTATION_PUBLIC_KEYS)
    internal val observability = V536RemoteObservability()
    private val observabilityStore = V538ObservabilityStore(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun recordEvent(event: V536RemoteEvent) {
        observability.record(event)
        observabilityStore.record(event)
    }

    suspend fun load(
        limit: Int = 100,
        symbols: List<String> = emptyList(),
        analysisTimeframeMinutes: Int = settings.analysisTimeframeMinutes,
        scanCadenceMinutes: Int = settings.scanCadenceMinutes,
        scanMode: ScanMode = ScanMode.MANUAL
    ): Result<Snapshot> = withContext(Dispatchers.IO) {
        try {
            val base = settings.baseUrl.trim().trimEnd('/')
            require(ProviderReadinessService.isValidHttps(base)) { "Canlı tarama için HTTPS backend adresi gerekli." }
            require(settings.apiKey.isNotBlank()) { "Canlı tarama için backend API anahtarı gerekli." }
            val safeLimit = limit.coerceIn(1, 200)
            require(analysisTimeframeMinutes in 1..239) {
                "Realtime scanner yalnız dakikalık 1..239 analiz periyotlarını destekler; 1 GÜN manuel gerçek günlük OHLCV taramasında kullanılır."
            }
            val safeTimeframe = analysisTimeframeMinutes
            val safeCadence = scanCadenceMinutes.coerceIn(1, 1440)
            val symbolQuery = symbols.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
                .take(200).joinToString(",")
            val urlBuilder = base.toHttpUrl().newBuilder()
                .addPathSegments("v1/realtime/opportunities")
                .addQueryParameter("limit", safeLimit.toString())
                .addQueryParameter("analysisTimeframeMinutes", safeTimeframe.toString())
                .addQueryParameter("scanCadenceMinutes", safeCadence.toString())
                .addQueryParameter("scanMode", scanMode.name)
            if (symbolQuery.isNotBlank()) urlBuilder.addQueryParameter("symbols", symbolQuery)
            val url = urlBuilder.build()
            recordEvent(V536RemoteEvent(V536RemoteEventType.REQUEST_STARTED, System.currentTimeMillis(), "START", ScoringConfig.ENGINE_VERSION, timeframeMinutes = safeTimeframe))
            val request = BackendRequestSecurity.apply(
                Request.Builder().url(url).header("Accept", "application/json"),
                settings
            ).get().build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "Realtime scanner HTTP ${response.code} döndürdü." }
                recordEvent(V536RemoteEvent(V536RemoteEventType.HTTP_SUCCESS, System.currentTimeMillis(), "HTTP_${response.code}", ScoringConfig.ENGINE_VERSION, timeframeMinutes = safeTimeframe))
                val body = response.body?.string().orEmpty()
                require(body.isNotBlank()) { "Realtime scanner boş yanıt döndürdü." }
                val clientReceiveElapsed = SystemClock.elapsedRealtime()
                val bootstrapTrustedNow = parseHttpDateMillis(response.header("Date"))
                    ?: throw IllegalArgumentException("HTTPS Date başlığı olmadan trusted-time bootstrap yapılamaz.")
                val snapshot = validateReplay(parse(JSONObject(body), bootstrapTrustedNow, clientReceiveElapsed))
                require(snapshot.analysisTimeframeMinutes == safeTimeframe) { "Backend analiz timeframe sözleşmesi uyuşmuyor." }
                require(snapshot.scanCadenceMinutes == safeCadence) { "Backend tarama cadence sözleşmesi uyuşmuyor." }
                require(snapshot.scanMode == scanMode) { "Backend scanMode sözleşmesi uyuşmuyor." }
                Result.success(snapshot)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            recordEvent(V536RemoteEvent(V536RemoteEventType.HTTP_FAILURE, System.currentTimeMillis(), t::class.java.simpleName, ScoringConfig.ENGINE_VERSION, timeframeMinutes = analysisTimeframeMinutes))
            Result.failure(t)
        }
    }

    internal fun validateReplay(snapshot: Snapshot): Snapshot {
        val report = replayStore.validateAndCommit(
            V538ReplayToken(
                providerId = snapshot.providerId,
                attestationKeyId = snapshot.attestationKeyId,
                attestationKeyGeneration = snapshot.attestationKeyGeneration,
                serverEpoch = snapshot.serverEpoch,
                serverEpochCreatedAt = snapshot.serverEpochCreatedAt,
                serverSequence = snapshot.serverSequence,
                nonce = snapshot.replayNonce,
                requestId = snapshot.requestId,
                snapshotId = snapshot.snapshotId,
                issuedAt = snapshot.replayIssuedAt,
                serverTime = snapshot.serverTime,
                expiresAt = snapshot.replayExpiresAt
            ),
            trustedNowMillis = snapshot.trustedNowMillis,
            allowedClockSkewMs = V536ClockPolicy.forTimeframe(snapshot.analysisTimeframeMinutes).futureToleranceMs
        )
        if (!report.accepted) {
            recordEvent(V536RemoteEvent(V536RemoteEventType.REPLAY_REJECTED, System.currentTimeMillis(), report.violations.joinToString("|"), ScoringConfig.ENGINE_VERSION, snapshot.providerId, snapshot.analysisTimeframeMinutes))
            throw IllegalArgumentException("REMOTE replay koruması ihlali: ${report.violations.joinToString(",")}")
        }
        return snapshot
    }

    internal fun parse(
        root: JSONObject,
        bootstrapTrustedNowMillis: Long = System.currentTimeMillis(),
        receiveElapsedRealtime: Long = SystemClock.elapsedRealtime()
    ): Snapshot {
        require(root.optString("mode") == "REALTIME_MARKET_ENGINE") { "Backend realtime scanner modu doğrulanamadı." }
        require(root.optBoolean("realtime", false)) { "Backend sonucu realtime olarak işaretlenmemiş." }
        require(!root.optBoolean("tradingViewUsedAsMarketData", true)) { "TradingView piyasa verisi olarak kullanılamaz." }
        val provider = root.optString("provider").trim()
        val providerId = root.optString("providerId").trim()
        val providerVersion = root.optString("providerVersion").trim()
        val sourceType = root.optString("sourceType").trim().uppercase()
        val snapshotId = root.optString("snapshotId").trim()
        val requestId = root.optString("requestId").trim()
        val serverTime = root.optLong("serverTime", 0L)
        val snapshotHash = root.optString("snapshotHash").trim()
        require(provider.isNotBlank()) { "Backend provider alanı zorunludur." }
        require(providerId.isNotBlank()) { "Backend providerId alanı zorunludur." }
        require(providerVersion.isNotBlank()) { "Backend providerVersion alanı zorunludur." }
        require(sourceType.isNotBlank()) { "Backend sourceType alanı zorunludur." }
        require(snapshotId.isNotBlank()) { "Backend snapshotId alanı zorunludur." }
        require(requestId.isNotBlank()) { "Backend requestId alanı zorunludur." }
        val generatedAt = root.optLong("generatedAt", 0L)
        require(root.has("analysisTimeframeMinutes")) { "Backend analysisTimeframeMinutes alanı zorunludur." }
        require(root.has("scanCadenceMinutes")) { "Backend scanCadenceMinutes alanı zorunludur." }
        require(root.has("scanMode")) { "Backend scanMode alanı zorunludur." }
        val analysisTimeframe = root.optInt("analysisTimeframeMinutes", 0)
        require(analysisTimeframe in 1..239) {
            "Realtime scanner geçersiz analiz timeframe döndürdü: $analysisTimeframe. 1 GÜN bu endpoint üzerinden temsil edilmez."
        }
        val attestationKeyId = root.optString("attestationKeyId").trim()
        val attestationKeyGeneration = root.optLong("attestationKeyGeneration", 0L)
        require(attestationKeyId.isNotBlank() && attestationKeyGeneration > 0L) {
            "REMOTE attestation provider/key metadata eksik."
        }
        val trustedTimeKey = V538TrustedTimeKey(providerId, attestationKeyId, attestationKeyGeneration)
        val trustedNowMillis = V538TrustedTimeRegistry.now(trustedTimeKey, bootstrapTrustedNowMillis, receiveElapsedRealtime)
        val attestationClockSkewMs = V536ClockPolicy.forTimeframe(analysisTimeframe).futureToleranceMs
        val snapshotClock = V536ClockIntegrity.validateSnapshot(serverTime, generatedAt, analysisTimeframe)
        require(snapshotClock.accepted) {
            "Realtime scanner serverTime/generatedAt sözleşmesi geçersiz: ${snapshotClock.violations.joinToString(",")}"
        }
        val cadence = root.optInt("scanCadenceMinutes", 0)
        require(cadence in 1..1440) { "Backend scanCadenceMinutes sözleşmesi geçersiz: $cadence." }
        val mode = runCatching { ScanMode.valueOf(root.optString("scanMode").uppercase()) }
            .getOrElse { throw IllegalArgumentException("Backend scanMode sözleşmesi geçersiz.") }
        require(root.has("universeVerified") && root.has("universeSymbols")) { "Backend universe metadata alanları zorunludur." }
        val universeVerified = root.optBoolean("universeVerified", false)
        val universeSymbols = root.optInt("universeSymbols", -1)
        require(universeSymbols >= 0) { "Backend universeSymbols negatif olamaz." }
        if (!universeVerified) require(universeSymbols == 0) { "Doğrulanmamış evren Tüm BIST gibi sunulamaz." }
        val minReadyBars = root.optInt("minReadyBars", 0)
        require(minReadyBars >= CandleReadinessPolicy.requiredClosedBars()) {
            "Backend readiness sözleşmesi yetersiz: minReadyBars=$minReadyBars; gerekli=${CandleReadinessPolicy.requiredClosedBars()}."
        }

        val calculationEngineVersion = root.optString("calculationEngineVersion").trim()
        val calculationEngineMode = root.optString("engineMode").trim().uppercase()
        require(calculationEngineVersion == ScoringConfig.ENGINE_VERSION) {
            "Backend calculationEngineVersion sözleşmesi uyuşmuyor: '$calculationEngineVersion'. Beklenen=${ScoringConfig.ENGINE_VERSION}."
        }
        require(calculationEngineMode == CalculationEngineMode.REMOTE.name) {
            "Backend engineMode REMOTE olarak işaretlenmemiş."
        }

        val trackedSymbols = root.optInt("trackedSymbols", -1)
        val freshSymbols = root.optInt("freshSymbols", -1)
        val readySymbols = root.optInt("readySymbols", -1)
        require(root.has("resultItemCount")) { "Backend resultItemCount alanı zorunludur." }
        val resultItemCount = root.optInt("resultItemCount", -1)
        val freshCoveragePct = root.finite("freshCoveragePct")
        val readyCoveragePct = root.finite("readyCoveragePct")
        val engineVersion = root.optLong("engineVersion", 0L)

        require(root.has("items")) { "Backend items alanı zorunludur." }
        val items = root.optJSONArray("items") ?: throw IllegalArgumentException("Backend items dizisi geçersiz.")
        require(resultItemCount == items.length()) { "Backend resultItemCount/items sayısı uyuşmuyor." }
        val itemRefs = buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: throw IllegalArgumentException("REMOTE item nesne değil: index=$i")
                require(item.optBoolean("ready", false) && item.optBoolean("confirmedClosedBar", false)) {
                    "Opportunity endpoint yalnız ready + confirmedClosedBar item taşıyabilir: index=$i"
                }
                val symbol = item.optString("symbol").trim().uppercase()
                val inputHash = item.optString("calculationInputHash").trim()
                val outputHash = item.optString("calculationOutputHash").trim()
                add(V533SnapshotItemRef(symbol, inputHash, outputHash))
            }
        }
        val opportunities = buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: throw IllegalArgumentException("REMOTE item nesne değil: index=$i")
                val parsed = item.toOpportunity(
                    expectedProviderId = providerId,
                    expectedProviderVersion = providerVersion,
                    snapshotId = snapshotId,
                    requestId = requestId,
                    sourceType = sourceType,
                    generatedAt = generatedAt,
                    serverTime = serverTime,
                    analysisTimeframeMinutes = analysisTimeframe,
                    scanCadenceMinutes = cadence,
                    scanMode = mode,
                    calculationEngineVersion = calculationEngineVersion
                )
                require(parsed != null) { "REMOTE item semantic integrity ihlali: index=$i" }
                add(parsed)
            }
        }
        require(opportunities.size == resultItemCount) { "REMOTE parsed opportunity count/resultItemCount uyuşmuyor." }
        val snapshotInvariant = V533SnapshotInvariant.evaluate(
            V533SnapshotMetadata(
                snapshotId = snapshotId,
                requestId = requestId,
                provider = provider,
                providerId = providerId,
                providerVersion = providerVersion,
                sourceType = sourceType,
                generatedAt = generatedAt,
                serverTime = serverTime,
                trackedSymbols = trackedSymbols,
                freshSymbols = freshSymbols,
                readySymbols = readySymbols,
                resultItemCount = resultItemCount,
                universeVerified = universeVerified,
                universeSymbols = universeSymbols,
                freshCoveragePct = freshCoveragePct,
                readyCoveragePct = readyCoveragePct,
                minReadyBars = minReadyBars,
                engineVersion = engineVersion,
                calculationEngineVersion = calculationEngineVersion,
                engineMode = calculationEngineMode,
                analysisTimeframeMinutes = analysisTimeframe,
                scanCadenceMinutes = cadence,
                scanMode = mode.name,
                snapshotHash = snapshotHash
            ),
            itemRefs
        )
        require(snapshotInvariant.accepted) {
            "REMOTE snapshot semantic integrity ihlali: ${snapshotInvariant.violations.joinToString(",")}"
        }
        val serverEpoch = root.optString("serverEpoch").trim()
        val serverEpochCreatedAt = root.optLong("serverEpochCreatedAt", 0L)
        val serverSequence = root.optLong("serverSequence", 0L)
        val replayNonce = root.optString("nonce").trim()
        val attestationAlgorithm = root.optString("attestationAlgorithm").trim()
        val attestationSignature = root.optString("attestationSignature").trim()
        val attestationIssuedAt = root.optLong("attestationIssuedAt", 0L)
        val attestationExpiresAt = root.optLong("attestationExpiresAt", 0L)
        require(serverEpoch.isNotBlank() && serverEpochCreatedAt > 0L && serverSequence > 0L && replayNonce.isNotBlank() && attestationSignature.isNotBlank()) {
            "REMOTE attestation/replay metadata eksik."
        }
        val attestation = V538ServerAttestationVerifier.verify(
            registry = attestationKeys,
            envelope = V538AttestationEnvelope(
                snapshotHash = snapshotHash,
                requestId = requestId,
                snapshotId = snapshotId,
                providerId = providerId,
                attestationKeyId = attestationKeyId,
                attestationKeyGeneration = attestationKeyGeneration,
                serverEpoch = serverEpoch,
                serverEpochCreatedAt = serverEpochCreatedAt,
                serverTime = serverTime,
                generatedAt = generatedAt,
                serverSequence = serverSequence,
                nonce = replayNonce,
                calculationEngineVersion = calculationEngineVersion,
                issuedAt = attestationIssuedAt,
                expiresAt = attestationExpiresAt
            ),
            keyId = attestationKeyId,
            keyGeneration = attestationKeyGeneration,
            algorithm = attestationAlgorithm,
            signatureBase64 = attestationSignature,
            trustedNowMillis = trustedNowMillis,
            allowedClockSkewMs = attestationClockSkewMs
        )
        if (attestation.status != V538AttestationStatus.VERIFIED) {
            recordEvent(V536RemoteEvent(V536RemoteEventType.ATTESTATION_REJECTED, System.currentTimeMillis(), attestation.reason, ScoringConfig.ENGINE_VERSION, providerId, analysisTimeframe))
            throw IllegalArgumentException("REMOTE server attestation doğrulanamadı: ${attestation.reason}")
        }
        V538TrustedTimeRegistry.markVerified(
            key = trustedTimeKey,
            serverEpoch = serverEpoch,
            serverTimeMillis = serverTime,
            elapsedRealtimeMillis = receiveElapsedRealtime
        )
        recordEvent(V536RemoteEvent(V536RemoteEventType.ATTESTATION_VERIFIED, System.currentTimeMillis(), "VERIFIED", ScoringConfig.ENGINE_VERSION, providerId, analysisTimeframe))
        recordEvent(V536RemoteEvent(V536RemoteEventType.SNAPSHOT_ACCEPTED, System.currentTimeMillis(), "SNAPSHOT_ACCEPTED", ScoringConfig.ENGINE_VERSION, providerId, analysisTimeframe))
        return Snapshot(
            provider = provider,
            providerId = providerId,
            providerVersion = providerVersion,
            sourceType = sourceType,
            snapshotId = snapshotId,
            requestId = requestId,
            serverTime = serverTime,
            snapshotHash = snapshotHash,
            generatedAt = generatedAt,
            serverEpoch = serverEpoch,
            serverEpochCreatedAt = serverEpochCreatedAt,
            serverSequence = serverSequence,
            replayNonce = replayNonce,
            replayIssuedAt = attestationIssuedAt,
            replayExpiresAt = attestationExpiresAt,
            attestationKeyId = attestationKeyId,
            attestationKeyGeneration = attestationKeyGeneration,
            attestationStatus = attestation.status,
            trustedNowMillis = trustedNowMillis,
            trackedSymbols = trackedSymbols,
            freshSymbols = freshSymbols,
            readySymbols = readySymbols,
            resultItemCount = resultItemCount,
            universeVerified = universeVerified,
            universeSymbols = universeSymbols,
            freshCoveragePct = freshCoveragePct,
            readyCoveragePct = readyCoveragePct,
            minReadyBars = minReadyBars,
            engineVersion = engineVersion,
            calculationEngineVersion = calculationEngineVersion,
            calculationEngineMode = CalculationEngineMode.REMOTE,
            analysisTimeframeMinutes = analysisTimeframe,
            scanCadenceMinutes = cadence,
            scanMode = mode,
            opportunities = opportunities
        )
    }

    private fun JSONObject.toOpportunity(
        expectedProviderId: String,
        expectedProviderVersion: String,
        sourceType: String,
        snapshotId: String,
        requestId: String,
        generatedAt: Long,
        serverTime: Long,
        analysisTimeframeMinutes: Int,
        scanCadenceMinutes: Int,
        scanMode: ScanMode,
        calculationEngineVersion: String
    ): Opportunity? {
        if (!optBoolean("ready", false) || !optBoolean("confirmedClosedBar", false)) return null
        val symbol = optString("symbol").trim().uppercase()
        val price = optDouble("price", Double.NaN)
        val exchangeTimestamp = optLong("exchangeTimestamp", 0L)
        val dataTimestamp = optLong("dataTimestamp", 0L)
        val receivedAt = optLong("receivedAt", 0L)
        val declaredDataAgeMs = optLong("dataAgeMs", Long.MAX_VALUE)
        if (!has("finalSignalScore") || !has("longScore") || !has("shortScore") ||
            !has("dataConfidenceScore") || !has("riskScore") || !has("conflictPenalty") ||
            !has("signalAvailableWeight") || !has("dominantDirection") || !has("reasonCodes") ||
            !has("scoreBreakdown") || !has("decisionState") || !has("signalValidity") ||
            !has("calculationInputSchema") || !has("calculationInputs") ||
            !has("calculationInputHash") || !has("calculationOutputHash") || !has("calculationOutputSchema") ||
            !has("qualityClass") || !has("timingStatus") || !has("marketRegime") || !has("marketRegimeConfidence") || !has("breakoutDirection") ||
            !has("source") || !has("providerId") || !has("providerVersion") || !has("sourceType") ||
            !has("snapshotId") || !has("requestId") || !has("dataTimestamp")) return null
        val finalSignalScore = optInt("finalSignalScore", -1)
        val legacyScore = optInt("score", finalSignalScore)
        val riskScore = optInt("riskScore", -1)
        val dataQuality = optInt("dataConfidenceScore", -1)
        val conflictPenalty = optInt("conflictPenalty", -1)
        val clockReport = V536ClockIntegrity.validateItem(
            V536ClockEvidence(
                serverTime = serverTime,
                generatedAt = generatedAt,
                exchangeTimestamp = exchangeTimestamp,
                dataTimestamp = dataTimestamp,
                receivedAt = receivedAt,
                declaredDataAgeMs = declaredDataAgeMs
            ),
            analysisTimeframeMinutes
        )
        if (symbol.isBlank() || !price.isFinite() || price <= 0.0 || !clockReport.accepted ||
            finalSignalScore !in 0..100 || legacyScore != finalSignalScore || riskScore !in 0..100 || dataQuality !in 0..100 ||
            conflictPenalty !in 0..ScoringConfig.MTF_CONFLICT_PENALTY_MAX) return null
        val dataAgeMs = clockReport.measuredDataAgeMs ?: return null

        if (!has("analysisTimeframeMinutes")) return null
        val itemTimeframe = optInt("analysisTimeframeMinutes", 0)
        if (itemTimeframe != analysisTimeframeMinutes) return null

        val requiredTechnicalFields = listOf(
            "stockTrend", "momentum", "relativeVolume", "rsi", "macd", "macdSignal", "atr", "sessionVwap",
            "ema20", "ema50", "ema200", "openInterestChangePct", "mtfConsensusScore", "mtfConsensusLabel",
            "mtfAvailableCount", "mtfExpectedCount", "spreadPct", "liquidityScore", "slippageSensitivity", "structureDistanceAtr", "contractRiskPct"
        )
        if (requiredTechnicalFields.any { !has(it) }) return null
        val stockTrend = optString("stockTrend").trim().uppercase()
        val momentum = optString("momentum").trim().uppercase()
        if (stockTrend.isBlank() || momentum.isBlank()) return null
        val topLevelInputs = runCatching {
            V535RemoteItemMirror(
                symbol = symbol,
                analysisTimeframeMinutes = analysisTimeframeMinutes,
                evaluationTimestamp = exchangeTimestamp,
                price = price,
                stockTrend = stockTrend,
                momentum = momentum,
                relativeVolume = strictNullableDouble("relativeVolume"),
                rsi14 = strictNullableDouble("rsi"),
                macd = strictNullableDouble("macd"),
                macdSignal = strictNullableDouble("macdSignal"),
                atr14 = strictNullableDouble("atr"),
                sessionVwap = strictNullableDouble("sessionVwap"),
                ema20 = strictNullableDouble("ema20"),
                ema50 = strictNullableDouble("ema50"),
                ema200 = strictNullableDouble("ema200"),
                openInterestChangePct = strictNullableDouble("openInterestChangePct"),
                mtfConsensusScore = strictNullableInt("mtfConsensusScore"),
                spreadPct = strictNullableDouble("spreadPct"),
                liquidityScore = strictNullableInt("liquidityScore"),
                slippageSensitivity = strictNullableDouble("slippageSensitivity"),
                structureDistanceAtr = strictNullableDouble("structureDistanceAtr"),
                contractRiskPct = strictNullableInt("contractRiskPct")
            )
        }.getOrNull() ?: return null
        val relativeVolume = topLevelInputs.relativeVolume
        val rsi = topLevelInputs.rsi14
        val macd = topLevelInputs.macd
        val macdSignal = topLevelInputs.macdSignal
        val atr = topLevelInputs.atr14
        val sessionVwap = topLevelInputs.sessionVwap
        val ema20 = topLevelInputs.ema20
        val ema50 = topLevelInputs.ema50
        val ema200 = topLevelInputs.ema200
        val direction = optString("direction").trim().uppercase()
        val dominantDirection = optString("dominantDirection").trim().uppercase()
        val longScore = optInt("longScore", -1)
        val shortScore = optInt("shortScore", -1)
        val decisionRaw = optString("decisionState").trim().uppercase()
        val validityRaw = optString("signalValidity").trim().uppercase()
        val signalAvailableWeight = optInt("signalAvailableWeight", -1)
        val mtfConsensusScore = topLevelInputs.mtfConsensusScore
        val mtfConsensusLabel = optString("mtfConsensusLabel").trim()
        val mtfAvailableCount = optInt("mtfAvailableCount", -1)
        val mtfExpectedCount = optInt("mtfExpectedCount", -1)
        if (!V535MtfSemantics.isConsistent(mtfConsensusScore, mtfConsensusLabel)) return null
        if (mtfExpectedCount != V540CalculationOutputSchema.EXPECTED_MTF_COUNT || mtfAvailableCount !in 0..mtfExpectedCount) return null
        if (mtfAvailableCount >= 3 && mtfConsensusScore == null) return null
        if (mtfAvailableCount < 3 && mtfConsensusScore != null) return null
        val breakdown = optJSONArray("scoreBreakdown").toStrings()
        val reasonCodes = optJSONArray("reasonCodes").toStrings()
        val calculationInputSchema = optString("calculationInputSchema").trim()
        val calculationInputHash = optString("calculationInputHash").trim()
        val calculationOutputHash = optString("calculationOutputHash").trim()
        if (calculationInputSchema != V535RemoteInputHash.SCHEMA) return null
        val canonicalInputJson = optJSONObject("calculationInputs") ?: return null
        if (canonicalInputJson.strictKeySet() != V535RemoteInputHash.REQUIRED_FIELDS) return null
        val canonicalInput = runCatching { canonicalInputJson.toV535CalculationInput() }.getOrNull() ?: return null
        if (V535RemoteInputHash.validate(canonicalInput).isNotEmpty()) return null
        if (!V535RemoteInputConsistency.validate(canonicalInput, topLevelInputs).accepted) return null
        if (!V535RemoteInputHash.hash(canonicalInput).equals(calculationInputHash, ignoreCase = true)) return null
        val itemProviderId = optString("providerId").trim()
        val itemProviderVersion = optString("providerVersion").trim()
        val itemSourceType = optString("sourceType").trim().uppercase()
        val itemSnapshotId = optString("snapshotId").trim()
        val itemRequestId = optString("requestId").trim()
        val source = optString("source").trim()
        if (itemProviderId != expectedProviderId || itemProviderVersion != expectedProviderVersion || source.isBlank() ||
            itemSourceType != sourceType || itemSnapshotId != snapshotId || itemRequestId != requestId) return null
        val calculationOutputSchema = optString("calculationOutputSchema").trim()
        val qualityClass = optString("qualityClass").trim()
        val timingStatus = optString("timingStatus").trim()
        val marketRegimeRawForHash = optString("marketRegime").trim().uppercase()
        val marketRegimeConfidenceForHash = if (has("marketRegimeConfidence")) optInt("marketRegimeConfidence", -1) else 0
        val breakoutDirectionForHash = optString("breakoutDirection").trim().uppercase()
        if (calculationOutputSchema != V540CalculationOutputSchema.SCHEMA) return null
        if (V540CalculationOutputSchema.validate(
                qualityClass, timingStatus, marketRegimeRawForHash, marketRegimeConfidenceForHash,
                breakoutDirectionForHash, finite("rr1") ?: finite("riskReward"), finite("rr2"),
                mtfConsensusScore, mtfAvailableCount, mtfExpectedCount
            ).isNotEmpty()) return null
        val rrForPublication = finite("rr1") ?: finite("riskReward")
        val rr2ForPublication = finite("rr2")
        val remoteMetadata = RemoteEngineMetadata(
            calculationEngineVersion = calculationEngineVersion,
            engineMode = CalculationEngineMode.REMOTE.name,
            finalSignalScore = finalSignalScore,
            longScore = longScore,
            shortScore = shortScore,
            dataConfidenceScore = dataQuality,
            riskScore = riskScore,
            conflictPenalty = conflictPenalty,
            signalAvailableWeight = signalAvailableWeight,
            mtfConsensusScore = mtfConsensusScore,
            mtfAvailableCount = mtfAvailableCount,
            mtfExpectedCount = mtfExpectedCount,
            dominantDirection = dominantDirection,
            direction = direction,
            decisionState = decisionRaw,
            signalValidity = validityRaw,
            reasonCodes = reasonCodes,
            scoreBreakdown = breakdown,
            calculationInputHash = calculationInputHash,
            calculationOutputHash = calculationOutputHash,
            calculationOutputSchema = calculationOutputSchema,
            qualityClass = qualityClass,
            timingStatus = timingStatus,
            marketRegime = marketRegimeRawForHash,
            marketRegimeConfidence = marketRegimeConfidenceForHash,
            breakoutDirection = breakoutDirectionForHash,
            openInterestChangePct = canonicalInput.openInterestChangePct,
            spreadPct = canonicalInput.spreadPct,
            liquidityScore = canonicalInput.liquidityScore,
            slippageSensitivity = canonicalInput.slippageSensitivity,
            structureDistanceAtr = canonicalInput.structureDistanceAtr,
            contractRiskPct = canonicalInput.contractRiskPct,
            mtfConsensusLabel = mtfConsensusLabel,
            riskReward1 = rrForPublication,
            riskReward2 = rr2ForPublication
        )
        if (!RemoteEngineContract.isAccepted(remoteMetadata)) return null
        val decision = DecisionState.valueOf(decisionRaw)
        val validity = SignalValidity.valueOf(validityRaw)
        val delaySeconds = (dataAgeMs / 1000L).toInt().coerceAtLeast(0)
        val dailyChange = finite("dailyChangePct")
        val marketRegimeRaw = optString("marketRegime").trim().uppercase()
        val marketRegime = marketRegimeRaw.takeIf { it.isNotBlank() && it != "UNKNOWN" } ?: "VERİ YOK"
        val marketRegimeConfidence = if (has("marketRegimeConfidence")) {
            optInt("marketRegimeConfidence", -1).takeIf { it in 0..100 } ?: return null
        } else 0
        val freshnessScore = V540FreshnessPolicy.score(dataAgeMs, 60_000L)
        val rr = rrForPublication
        val engineDirectionForGate = when (direction.trim().uppercase()) {
            "LONG" -> tr.borsatakip.v5.analysis.v531.V531Direction.LONG
            "SHORT" -> tr.borsatakip.v5.analysis.v531.V531Direction.SHORT
            else -> tr.borsatakip.v5.analysis.v531.V531Direction.WATCH
        }
        val rrGate = V539RiskRewardGate.evaluate(engineDirectionForGate, rr)
        val mtfSufficient = mtfAvailableCount >= V540RankingEngine.MIN_MTF_COUNT_FOR_RANKING
        val finalPublishedDirection = if (mtfSufficient) rrGate.publishedDirection else tr.borsatakip.v5.analysis.v531.V531Direction.WATCH
        val effectiveGateCode = if (mtfSufficient) rrGate.code else "MTF_INSUFFICIENT_BLOCK"
        val publishedDirection = when (finalPublishedDirection) {
            tr.borsatakip.v5.analysis.v531.V531Direction.LONG -> "LONG"
            tr.borsatakip.v5.analysis.v531.V531Direction.SHORT -> "SHORT"
            tr.borsatakip.v5.analysis.v531.V531Direction.WATCH -> "NEUTRAL"
        }
        val publishedDecision = if (finalPublishedDirection == tr.borsatakip.v5.analysis.v531.V531Direction.WATCH) DecisionState.WATCH else decision
        val publishedValidity = if (finalPublishedDirection == tr.borsatakip.v5.analysis.v531.V531Direction.WATCH) SignalValidity.WATCH else validity
        val rankingResult = V540RankingEngine.evaluate(
            finalSignalScore = finalSignalScore,
            dataConfidence = dataQuality,
            riskScore = riskScore,
            relativeVolume = relativeVolume,
            freshnessScore = freshnessScore,
            rrGateCode = effectiveGateCode,
            published = finalPublishedDirection != tr.borsatakip.v5.analysis.v531.V531Direction.WATCH,
            mtfAvailableCount = mtfAvailableCount,
            mtfExpectedCount = mtfExpectedCount
        )
        val canonicalRanking = rankingResult.score
        val technical = TechnicalSnapshot(
            ema20 = ema20,
            ema50 = ema50,
            ema200 = ema200,
            rsi14 = rsi,
            macd = macd,
            macdSignal = macdSignal,
            bbUpper = null,
            bbLower = null,
            atr14 = atr,
            vwap = sessionVwap,
            volumeRatio = relativeVolume,
            support = null,
            resistance = null,
            sessionVwap = sessionVwap
        )
        return Opportunity(
            symbol = symbol,
            companyName = null,
            price = price,
            dailyChangePct = dailyChange,
            score = finalSignalScore,
            riskScore = riskScore,
            direction = publishedDirection,
            technicalLabel = when {
                finalSignalScore >= 85 -> "Çok Güçlü"
                finalSignalScore >= 70 -> "Güçlü"
                finalSignalScore >= 55 -> "Orta"
                else -> "İzleme"
            },
            volumeLabel = relativeVolume?.let { "%.2fx".format(it) } ?: "Veri yok",
            kapLabel = "Veri yok",
            liquidityLabel = topLevelInputs.liquidityScore?.let { "Likidite $it/100" } ?: "Veri yok",
            support = null,
            resistance = null,
            source = "$source • REMOTE ${ScoringConfig.ENGINE_VERSION}",
            dataTimestamp = exchangeTimestamp,
            candles = emptyList(),
            technical = technical,
            scoreBreakdown = breakdown + listOf("V540_GATE=$effectiveGateCode") + rankingResult.audit,
            dataConfidenceScore = dataQuality,
            dataConfidenceLabel = when {
                dataQuality >= 85 -> "Yüksek"
                dataQuality >= 65 -> "Orta"
                else -> "Düşük"
            },
            finalSignalScore = finalSignalScore,
            volumeDirectionLabel = relativeVolume?.let { "Göreli hacim %.2fx".format(it) } ?: "Hacim oranı yok",
            isRealtime = true,
            delaySeconds = delaySeconds,
            currentSessionIncluded = true,
            exchangeTimestamp = exchangeTimestamp,
            receivedAt = receivedAt,
            receivedElapsedRealtime = SystemClock.elapsedRealtime(),
            signalGeneratedAt = generatedAt,
            clientReceivedAt = System.currentTimeMillis(),
            dataMode = DataMode.REALTIME,
            signalValidity = publishedValidity,
            signalValidityReason = "Kapalı ${ScanTimeframe.displayLabel(analysisTimeframeMinutes)} mum • $momentum • hisse trendi=$stockTrend • veri yaşı=${dataAgeMs}ms • GATE=$effectiveGateCode",
            longScore = longScore,
            shortScore = shortScore,
            decisionState = publishedDecision,
            dataAgeMs = dataAgeMs,
            setupType = when (optString("breakoutDirection").uppercase()) {
                "UP" -> "TEYİTLİ YUKARI KIRILIM"
                "DOWN" -> "TEYİTLİ AŞAĞI KIRILIM"
                else -> "TEYİTLİ MOMENTUM"
            },
            // Realtime backend riskPlan sağlamıyorsa istemci puandan A sınıfı uydurmaz.
            // Typed kalite/zamanlama yalnız backend açıkça gönderirse taşınır; aksi halde izleme/unknown kalır.
            qualityClass = if (publishedDecision == DecisionState.WATCH) "İZLE" else qualityClass.takeIf { it.isNotBlank() } ?: "İZLE",
            timingStatus = timingStatus.takeIf { it.isNotBlank() } ?: "DEĞERLENDİRİLMEDİ",
            volumeAnomalyPct = relativeVolume?.let { (it - 1.0) * 100.0 },
            signalScore = finalSignalScore,
            rankingScore = canonicalRanking,
            rankingStatus = rankingResult.status,
            mtfAvailableCount = mtfAvailableCount,
            mtfExpectedCount = mtfExpectedCount,
            mtfCompletenessPct = rankingResult.mtfCompletenessPct,
            mtfConsensusScore = mtfConsensusScore,
            mtfConsensusLabel = V535MtfSemantics.labelFor(mtfConsensusScore),
            marketRegime = marketRegime,
            marketRegimeConfidence = if (marketRegime == "VERİ YOK") 0 else marketRegimeConfidence,
            analysisTimeframeMinutes = analysisTimeframeMinutes,
            scanCadenceMinutes = scanCadenceMinutes,
            scanMode = scanMode,
            calculationEngineVersion = calculationEngineVersion,
            calculationEngineMode = CalculationEngineMode.REMOTE,
            signalAvailableWeight = signalAvailableWeight,
            signalReasonCodes = (reasonCodes + effectiveGateCode).distinct(),
            signalConflictPenalty = conflictPenalty,
            providerId = expectedProviderId,
            providerVersion = expectedProviderVersion,
            sourceType = sourceType,
            snapshotId = snapshotId,
            requestId = requestId,
            calculationInputHash = calculationInputHash,
            calculationOutputHash = calculationOutputHash,
            provenanceGeneratedAt = generatedAt,
            serverTime = serverTime,
            openInterestChangePct = canonicalInput.openInterestChangePct,
            spreadPct = canonicalInput.spreadPct,
            liquidityScore = canonicalInput.liquidityScore,
            slippageSensitivity = canonicalInput.slippageSensitivity,
            structureDistanceAtr = canonicalInput.structureDistanceAtr,
            contractRiskPct = canonicalInput.contractRiskPct
        )
    }

    private fun JSONObject.toV535CalculationInput(): V535RemoteCalculationInput? {
        if (strictKeySet() != V535RemoteInputHash.REQUIRED_FIELDS) return null
        val symbol = optString("symbol").trim().uppercase()
        val timeframe = strictInt("analysisTimeframeMinutes")
        val evaluationTimestamp = strictLong("evaluationTimestamp")
        val price = strictDouble("price")
        val stockTrend = optString("stockTrend").trim().uppercase()
        val momentum = optString("momentum").trim().uppercase()
        if (symbol.isBlank() || timeframe !in 1..239 || evaluationTimestamp <= 0L || price <= 0.0 || stockTrend.isBlank() || momentum.isBlank()) return null
        return V535RemoteCalculationInput(
            symbol = symbol,
            analysisTimeframeMinutes = timeframe,
            evaluationTimestamp = evaluationTimestamp,
            price = price,
            stockTrend = stockTrend,
            momentum = momentum,
            relativeVolume = strictNullableDouble("relativeVolume"),
            rsi14 = strictNullableDouble("rsi14"),
            macd = strictNullableDouble("macd"),
            macdSignal = strictNullableDouble("macdSignal"),
            atr14 = strictNullableDouble("atr14"),
            sessionVwap = strictNullableDouble("sessionVwap"),
            ema20 = strictNullableDouble("ema20"),
            ema50 = strictNullableDouble("ema50"),
            ema200 = strictNullableDouble("ema200"),
            openInterestChangePct = strictNullableDouble("openInterestChangePct"),
            mtfConsensusScore = strictNullableInt("mtfConsensusScore"),
            spreadPct = strictNullableDouble("spreadPct"),
            liquidityScore = strictNullableInt("liquidityScore"),
            slippageSensitivity = strictNullableDouble("slippageSensitivity"),
            structureDistanceAtr = strictNullableDouble("structureDistanceAtr"),
            contractRiskPct = strictNullableInt("contractRiskPct")
        )
    }

    private fun JSONObject.strictKeySet(): Set<String> = buildSet {
        val iterator = keys()
        while (iterator.hasNext()) add(iterator.next())
    }

    private fun JSONObject.strictDouble(name: String): Double {
        if (!has(name) || isNull(name)) throw IllegalArgumentException("Missing numeric field: $name")
        val raw = opt(name) as? Number ?: throw IllegalArgumentException("Invalid numeric field: $name")
        val value = raw.toDouble()
        if (!value.isFinite()) throw IllegalArgumentException("Non-finite numeric field: $name")
        return value
    }

    private fun JSONObject.strictLong(name: String): Long {
        val value = strictDouble(name)
        if (value % 1.0 != 0.0 || value < Long.MIN_VALUE.toDouble() || value > Long.MAX_VALUE.toDouble()) {
            throw IllegalArgumentException("Invalid long field: $name")
        }
        return value.toLong()
    }

    private fun JSONObject.strictInt(name: String): Int {
        val value = strictDouble(name)
        if (value % 1.0 != 0.0 || value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) {
            throw IllegalArgumentException("Invalid integer field: $name")
        }
        return value.toInt()
    }

    private fun JSONObject.strictNullableDouble(name: String): Double? {
        if (!has(name)) throw IllegalArgumentException("Missing nullable numeric field: $name")
        if (isNull(name)) return null
        return strictDouble(name)
    }

    private fun JSONObject.strictNullableInt(name: String): Int? {
        if (!has(name)) throw IllegalArgumentException("Missing nullable integer field: $name")
        if (isNull(name)) return null
        return strictInt(name)
    }

    private fun JSONObject.finite(name: String): Double? =
        if (!has(name) || isNull(name)) null else optDouble(name, Double.NaN).takeIf { it.isFinite() }

    private fun JSONArray?.toStrings(): List<String> = if (this == null) emptyList() else buildList {
        for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let(::add)
    }
    private fun parseHttpDateMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
    }

}
