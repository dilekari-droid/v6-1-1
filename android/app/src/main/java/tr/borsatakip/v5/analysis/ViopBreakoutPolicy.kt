package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Candle

object ViopBreakoutPolicy {
    data class Result(
        val volumeAnomalyPct: Double?,
        val state: String,
        val confirmed: Boolean,
        val longContribution: Int,
        val shortContribution: Int
    )

    fun evaluate(candles: List<Candle>, volumeRatio: Double?): Result {
        val r = BreakoutVolumeEngine.evaluate(candles, volumeRatio)
        return Result(r.volumeAnomalyPct, r.state, r.confirmed, r.longContribution, r.shortContribution)
    }
}
