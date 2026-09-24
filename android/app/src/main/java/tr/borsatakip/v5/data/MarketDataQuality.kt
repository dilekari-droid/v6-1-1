package tr.borsatakip.v5.data

import tr.borsatakip.v5.model.MarketDataMetadata
import tr.borsatakip.v5.model.MarketDataState
import tr.borsatakip.v5.model.Stock
import java.util.concurrent.ConcurrentHashMap

/** Fail-closed market-data semantics shared by HTTP, fallback and WebSocket paths. */
object MarketDataQuality {
    const val MAX_REASONABLE_PRICE = 100_000_000.0
    const val MAX_FUTURE_SKEW_MS = 30_000L
    const val MAX_LIVE_AGE_MS = MarketPresentationPolicy.FRESH_MAX_AGE_MS
    const val MAX_DELAYED_AGE_MS = MarketPresentationPolicy.KNOWN_MAX_AGE_MS

    data class Verdict(val accepted: Boolean, val code: String, val reason: String)

    fun validateStock(expectedSymbol: String, stock: Stock, nowMs: Long = System.currentTimeMillis()): Verdict {
        val expected = expectedSymbol.trim().uppercase()
        val actual = stock.symbol.trim().uppercase()
        if (expected.isBlank() || actual != expected) return Verdict(false, "SYMBOL_MISMATCH", "İstenen sembol ile yanıt sembolü uyuşmuyor.")
        val price = stock.quotePrice ?: stock.candles.lastOrNull()?.close
        if (price == null || !price.isFinite() || price <= 0.0 || price > MAX_REASONABLE_PRICE) {
            return Verdict(false, "INVALID_PRICE", "Fiyat geçersiz veya mantıksız aralıkta.")
        }
        val ts = stock.exchangeTimestamp
        if (ts <= 0L) return Verdict(false, "MISSING_TIMESTAMP", "Piyasa veri zamanı eksik.")
        if (ts > nowMs + MAX_FUTURE_SKEW_MS) return Verdict(false, "FUTURE_TIMESTAMP", "Piyasa veri zamanı gelecekte.")
        val age = (nowMs - ts).coerceAtLeast(0L)
        val maxAge = if (stock.isRealtime) MAX_LIVE_AGE_MS else MAX_DELAYED_AGE_MS
        if (age > maxAge) return Verdict(false, "STALE_TIMESTAMP", "Piyasa verisi eski: ${age} ms.")
        if (stock.candles.isNotEmpty()) {
            val candleError = RealTimeIntegrityPolicy.validateCandles(stock.candles, nowMs)
            if (candleError != null) return Verdict(false, "INVALID_OHLCV", candleError)
        }
        if (stock.isRealtime) {
            if (!stock.currentSessionIncluded) return Verdict(false, "SESSION_MISMATCH", "Canlı veri mevcut seansı içermiyor.")
            val delay = stock.delaySeconds ?: return Verdict(false, "DELAY_UNKNOWN", "Canlı veri gecikmesi doğrulanamadı.")
            if (delay !in 0..RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS) {
                return Verdict(false, "DELAY_TOO_HIGH", "Canlı veri gecikmesi sınırı aşıyor.")
            }
        }
        return Verdict(true, "OK", "Veri semantik olarak geçerli.")
    }

    fun metadata(
        providerId: String,
        source: String,
        marketTimestamp: Long,
        receivedAt: Long,
        isLive: Boolean,
        delaySeconds: Int?,
        lastSuccessfulUpdateAt: Long,
        fallback: Boolean,
        offline: Boolean = false,
        reason: String = ""
    ): MarketDataMetadata {
        val now = System.currentTimeMillis()
        val age = if (marketTimestamp > 0L) (now - marketTimestamp).coerceAtLeast(0L) else Long.MAX_VALUE
        val delayed = !isLive || fallback || (delaySeconds ?: Int.MAX_VALUE) > RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS
        val state = when {
            offline -> MarketDataState.OFFLINE
            marketTimestamp <= 0L -> MarketDataState.UNKNOWN
            age > MAX_DELAYED_AGE_MS -> MarketDataState.STALE
            fallback -> MarketDataState.FALLBACK
            isLive && !delayed -> MarketDataState.LIVE
            else -> MarketDataState.DELAYED
        }
        return MarketDataMetadata(
            providerId = providerId,
            source = source,
            marketDataTimestamp = marketTimestamp,
            deviceReceivedAt = receivedAt,
            isLive = state == MarketDataState.LIVE,
            isDelayed = state != MarketDataState.LIVE,
            delayDurationMs = delaySeconds?.coerceAtLeast(0)?.times(1000L),
            lastSuccessfulUpdateAt = lastSuccessfulUpdateAt,
            state = state,
            isFallback = fallback,
            isOffline = offline,
            qualityReason = reason
        )
    }

    fun dataAgeMs(metadata: MarketDataMetadata?, nowMs: Long = System.currentTimeMillis()): Long? =
        metadata?.marketDataTimestamp?.takeIf { it > 0L }?.let { (nowMs - it).coerceAtLeast(0L) }

    fun providerStatus(metadata: MarketDataMetadata?, nowMs: Long = System.currentTimeMillis()): ProviderStatusPresentation =
        MarketPresentationPolicy.providerStatus(
            providerId = metadata?.providerId.orEmpty(),
            source = metadata?.source.orEmpty(),
            rawState = metadata?.state?.name,
            dataAgeMs = dataAgeMs(metadata, nowMs)
        )

    fun uiStatus(metadata: MarketDataMetadata?): String = providerStatus(metadata).stateLabel
}

/** Process-wide circuit-breaker plus provider+symbol repeated-data detector. */
object ProviderHealthRegistry {
    data class Snapshot(
        val providerId: String,
        val healthy: Boolean,
        val consecutiveFailures: Int,
        val circuitOpenUntil: Long,
        val lastSuccessfulUpdateAt: Long,
        val lastFailureReason: String,
        val repeatedPayloadCount: Int,
        val recovering: Boolean,
        val recoverySuccesses: Int
    )

    private data class MutableState(
        var consecutiveFailures: Int = 0,
        var circuitOpenUntil: Long = 0L,
        var lastSuccessfulUpdateAt: Long = 0L,
        var lastFailureReason: String = "",
        var repeatedPayloadCount: Int = 0,
        var recovering: Boolean = false,
        var recoverySuccesses: Int = 0
    )

    private data class SymbolState(
        var lastSignature: String? = null,
        var repeatedPayloadCount: Int = 0,
        var lastSignatureSeenAt: Long = 0L
    )

    private val states = ConcurrentHashMap<String, MutableState>()
    private val symbolStates = ConcurrentHashMap<String, SymbolState>()
    private const val FAILURE_THRESHOLD = 3
    private const val COOLDOWN_MS = 30_000L
    private const val MAX_REPEATED_REALTIME_PAYLOADS = 3
    private const val RECOVERY_VALIDATION_SUCCESSES = 2

    @Synchronized
    fun circuitAllows(providerId: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        state(providerId).circuitOpenUntil <= nowMs

    /** Compatibility path for non-symbol streams such as a WebSocket connection itself. */
    @Synchronized
    fun recordSuccess(providerId: String, signature: String?, realtime: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean =
        recordSymbolSuccessLocked(providerId, "__provider__", signature, realtime, nowMs)

    /** Repeated payload detection is isolated per (providerId, symbol). */
    @Synchronized
    fun recordSymbolSuccess(
        providerId: String,
        symbol: String,
        signature: String?,
        realtime: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = recordSymbolSuccessLocked(providerId, symbol.trim().uppercase().ifBlank { "__unknown__" }, signature, realtime, nowMs)

    private fun recordSymbolSuccessLocked(
        providerId: String,
        symbol: String,
        signature: String?,
        realtime: Boolean,
        nowMs: Long
    ): Boolean {
        val s = state(providerId)
        val key = "$providerId|$symbol"
        val ss = symbolStates.getOrPut(key) { SymbolState() }
        if (signature != null && signature == ss.lastSignature) ss.repeatedPayloadCount++ else ss.repeatedPayloadCount = 0
        ss.lastSignature = signature
        ss.lastSignatureSeenAt = nowMs
        s.repeatedPayloadCount = ss.repeatedPayloadCount

        if (realtime && ss.repeatedPayloadCount >= MAX_REPEATED_REALTIME_PAYLOADS) {
            s.lastFailureReason = "Aynı gerçek zamanlı veri $symbol için sürekli tekrarlandı."
            s.consecutiveFailures = FAILURE_THRESHOLD
            s.circuitOpenUntil = nowMs + COOLDOWN_MS
            s.recovering = true
            s.recoverySuccesses = 0
            return false
        }

        s.lastSuccessfulUpdateAt = nowMs
        if (s.recovering) {
            s.recoverySuccesses++
            if (s.recoverySuccesses < RECOVERY_VALIDATION_SUCCESSES) {
                s.lastFailureReason = "Provider iyileşme doğrulamasında (${s.recoverySuccesses}/$RECOVERY_VALIDATION_SUCCESSES)."
                s.consecutiveFailures = 0
                s.circuitOpenUntil = 0L
                return false
            }
            s.recovering = false
            s.recoverySuccesses = 0
        }
        s.consecutiveFailures = 0
        s.circuitOpenUntil = 0L
        s.lastFailureReason = ""
        return true
    }

    @Synchronized
    fun recordFailure(providerId: String, reason: String, nowMs: Long = System.currentTimeMillis()) {
        val s = state(providerId)
        s.consecutiveFailures++
        s.lastFailureReason = reason
        if (s.consecutiveFailures >= FAILURE_THRESHOLD) {
            s.circuitOpenUntil = nowMs + COOLDOWN_MS
            s.recovering = true
            s.recoverySuccesses = 0
        }
    }

    @Synchronized
    fun lastSuccessfulUpdateAt(providerId: String): Long = state(providerId).lastSuccessfulUpdateAt

    @Synchronized
    fun snapshot(providerId: String, nowMs: Long = System.currentTimeMillis()): Snapshot {
        val s = state(providerId)
        return Snapshot(
            providerId = providerId,
            healthy = s.consecutiveFailures < FAILURE_THRESHOLD && s.circuitOpenUntil <= nowMs && !s.recovering,
            consecutiveFailures = s.consecutiveFailures,
            circuitOpenUntil = s.circuitOpenUntil,
            lastSuccessfulUpdateAt = s.lastSuccessfulUpdateAt,
            lastFailureReason = s.lastFailureReason,
            repeatedPayloadCount = s.repeatedPayloadCount,
            recovering = s.recovering,
            recoverySuccesses = s.recoverySuccesses
        )
    }

    @Synchronized
    fun repeatedPayloadCount(providerId: String, symbol: String): Int =
        symbolStates["$providerId|${symbol.trim().uppercase()}"]?.repeatedPayloadCount ?: 0

    @Synchronized
    internal fun resetForTests() {
        states.clear()
        symbolStates.clear()
    }

    private fun state(providerId: String): MutableState = states.getOrPut(providerId) { MutableState() }
}
