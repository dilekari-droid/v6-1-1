package tr.borsatakip.v5.data

import tr.borsatakip.v5.model.Candle

/**
 * Live quote may arrive while the newest OHLCV candle is still forming.
 * Technical decisions must use only completed candles; the current quote remains separate.
 */
object RealtimeClosedBarPolicy {
    fun selectClosedIntraday(
        candles: List<Candle>,
        frameMinutes: Int,
        nowMs: Long = System.currentTimeMillis()
    ): List<Candle> {
        require(frameMinutes in 1..239) { "frameMinutes must be 1..239" }
        val frameMs = frameMinutes * 60_000L
        return candles.dropLastWhile { c -> c.timestamp <= 0L || nowMs < c.timestamp + frameMs }
    }

    /**
     * Provider candle timestamps are normally bar-start times. Therefore the newest completed
     * bar can legitimately be almost two frame lengths behind a current quote just before the
     * next candle closes. Two extra minutes cover clock/network jitter without accepting old data.
     */
    fun maximumClosedBarAgeMs(frameMinutes: Int): Long {
        require(frameMinutes in 1..239) { "frameMinutes must be 1..239" }
        return (frameMinutes * 2L + 2L) * 60_000L
    }
}
