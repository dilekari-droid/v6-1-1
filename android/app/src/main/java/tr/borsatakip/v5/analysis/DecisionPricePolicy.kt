package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Candle

/** Keeps decision math on history-bar prices while live quote stays presentation-only. */
object DecisionPricePolicy {
    fun historyBarPrice(candles: List<Candle>): Double? =
        OhlcvResampler.sanitize(candles).lastOrNull()?.close?.takeIf { it.isFinite() && it > 0.0 }

    /** Compatibility overload. Live quote is intentionally ignored and never used as a decision fallback. */
    fun historyBarPrice(candles: List<Candle>, liveFallback: Double): Double? {
        @Suppress("UNUSED_VARIABLE") val ignoredLiveQuote = liveFallback
        return historyBarPrice(candles)
    }
}
