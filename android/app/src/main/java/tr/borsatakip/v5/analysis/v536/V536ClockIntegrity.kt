package tr.borsatakip.v5.analysis.v536

import kotlin.math.abs

data class V536ClockPolicy(
    val futureToleranceMs: Long,
    val dataAgeToleranceMs: Long,
    val maxDataAgeMs: Long = 60_000L,
    val maxGenerationAgeMs: Long = 60_000L
) {
    companion object {
        fun forTimeframe(minutes: Int): V536ClockPolicy = when (minutes) {
            1 -> V536ClockPolicy(3_000L, 3_000L)
            in 2..5 -> V536ClockPolicy(5_000L, 5_000L)
            in 6..15 -> V536ClockPolicy(7_500L, 7_500L)
            in 16..60 -> V536ClockPolicy(10_000L, 10_000L)
            in 61..239 -> V536ClockPolicy(15_000L, 15_000L)
            else -> throw IllegalArgumentException("Unsupported timeframe: $minutes")
        }
    }
}

data class V536ClockEvidence(
    val serverTime: Long,
    val generatedAt: Long,
    val exchangeTimestamp: Long,
    val dataTimestamp: Long,
    val receivedAt: Long,
    val declaredDataAgeMs: Long
)

data class V536ClockReport(
    val accepted: Boolean,
    val violations: List<String>,
    val measuredDataAgeMs: Long?
)

object V536ClockIntegrity {
    fun validateSnapshot(serverTime: Long, generatedAt: Long, timeframeMinutes: Int): V536ClockReport {
        val p = V536ClockPolicy.forTimeframe(timeframeMinutes)
        val v = mutableListOf<String>()
        if (serverTime <= 0L) v += "SERVER_TIME_INVALID"
        if (generatedAt <= 0L) v += "GENERATED_AT_INVALID"
        if (serverTime > 0L && generatedAt > serverTime + p.futureToleranceMs) v += "GENERATED_IN_FUTURE"
        if (serverTime > 0L && generatedAt > 0L && serverTime - generatedAt > p.maxGenerationAgeMs) v += "GENERATED_TOO_OLD"
        return V536ClockReport(v.isEmpty(), v.distinct(), null)
    }

    fun validateItem(e: V536ClockEvidence, timeframeMinutes: Int): V536ClockReport {
        val p = V536ClockPolicy.forTimeframe(timeframeMinutes)
        val v = mutableListOf<String>()
        val snapshot = validateSnapshot(e.serverTime, e.generatedAt, timeframeMinutes)
        if (!snapshot.accepted) v += snapshot.violations
        if (e.exchangeTimestamp <= 0L) v += "EXCHANGE_TIME_INVALID"
        if (e.dataTimestamp <= 0L) v += "DATA_TIME_INVALID"
        if (e.receivedAt <= 0L) v += "RECEIVED_AT_INVALID"
        if (e.dataTimestamp != e.exchangeTimestamp) v += "DATA_EXCHANGE_TIME_MISMATCH"
        if (e.declaredDataAgeMs !in 0..p.maxDataAgeMs) v += "DECLARED_DATA_AGE_RANGE"

        val measured = if (e.serverTime > 0L && e.exchangeTimestamp > 0L) e.serverTime - e.exchangeTimestamp else null
        if (measured != null) {
            if (measured < -p.futureToleranceMs) v += "EXCHANGE_IN_FUTURE"
            if (measured > p.maxDataAgeMs) v += "EXCHANGE_TOO_OLD"
            if (abs(e.declaredDataAgeMs - measured.coerceAtLeast(0L)) > p.dataAgeToleranceMs) v += "DATA_AGE_MISMATCH"
        }
        if (e.receivedAt > 0L && e.serverTime > 0L && e.receivedAt > e.serverTime + p.futureToleranceMs) v += "RECEIVED_AFTER_SERVER"
        if (e.receivedAt > 0L && e.exchangeTimestamp > 0L && e.receivedAt + p.futureToleranceMs < e.exchangeTimestamp) v += "RECEIVED_BEFORE_EXCHANGE"
        if (e.generatedAt > 0L && e.exchangeTimestamp > 0L && e.exchangeTimestamp > e.generatedAt + p.futureToleranceMs) v += "EXCHANGE_AFTER_GENERATION"
        return V536ClockReport(v.isEmpty(), v.distinct(), measured?.coerceAtLeast(0L))
    }
}
