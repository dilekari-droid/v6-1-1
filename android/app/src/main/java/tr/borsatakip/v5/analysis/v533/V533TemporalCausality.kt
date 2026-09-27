package tr.borsatakip.v5.analysis.v533

import tr.borsatakip.v5.model.Candle

data class V533TemporalEvidence(
    val evaluationTimestamp: Long,
    val technicalAsOfTimestamp: Long,
    val openInterestAsOfTimestamp: Long? = null,
    val mtfAsOfTimestamp: Long? = null
)

data class V533TemporalReport(val accepted: Boolean, val violations: List<String>)

object V533TemporalCausality {
    fun candlesAtOrBefore(candles: List<Candle>, asOfTimestamp: Long): List<Candle> = candles.asSequence()
        .filter { it.timestamp > 0L && it.timestamp <= asOfTimestamp }
        .sortedBy { it.timestamp }
        .toList()

    fun evaluate(candles: List<Candle>, evidence: V533TemporalEvidence): V533TemporalReport {
        val v = mutableListOf<String>()
        if (evidence.evaluationTimestamp <= 0L) v += "EVALUATION_TIME_INVALID"
        if (evidence.technicalAsOfTimestamp <= 0L || evidence.technicalAsOfTimestamp > evidence.evaluationTimestamp) v += "TECHNICAL_LOOKAHEAD"
        if (evidence.openInterestAsOfTimestamp != null && (evidence.openInterestAsOfTimestamp <= 0L || evidence.openInterestAsOfTimestamp > evidence.evaluationTimestamp)) v += "OI_TIME_INVALID"
        if (evidence.mtfAsOfTimestamp != null && (evidence.mtfAsOfTimestamp <= 0L || evidence.mtfAsOfTimestamp > evidence.evaluationTimestamp)) v += "MTF_TIME_INVALID"
        if (candles.isEmpty()) v += "CANDLES_EMPTY"
        if (candles.any { it.timestamp <= 0L }) v += "CANDLE_TIME_INVALID"
        if (candles.any { it.timestamp > evidence.evaluationTimestamp }) v += "FUTURE_CANDLE_PRESENT"
        if (candles.zipWithNext().any { (a, b) -> a.timestamp >= b.timestamp }) v += "CANDLE_ORDER_INVALID"
        return V533TemporalReport(v.isEmpty(), v.distinct())
    }
}
