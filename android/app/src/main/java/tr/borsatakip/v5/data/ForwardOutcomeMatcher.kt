package tr.borsatakip.v5.data

import tr.borsatakip.v5.model.Candle
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

object ForwardOutcomeMatcher {
    val horizons: LinkedHashMap<String, Long> = linkedMapOf(
        "15m" to 15 * 60_000L,
        "30m" to 30 * 60_000L,
        "1h" to 60 * 60_000L,
        "4h" to 4 * 60 * 60_000L,
        "1d" to 24 * 60 * 60_000L
    )

    fun dueHorizons(signalGeneratedAt: Long, now: Long, alreadyPresent: Set<String> = emptySet()): Map<String, Long> =
        horizons.filter { (name, horizon) ->
            name !in alreadyPresent &&
                signalGeneratedAt > 0L &&
                horizon > 0L &&
                signalGeneratedAt <= Long.MAX_VALUE - horizon &&
                now >= signalGeneratedAt + horizon
        }

    /** Advances a wall-clock horizon to the next BIST trading session when it lands outside 09:40-18:10. */
    fun tradingTargetTime(signalGeneratedAt: Long, horizonMs: Long, zone: ZoneId = ZoneId.of("Europe/Istanbul")): Long {
        if (signalGeneratedAt <= 0L) return 0L
        var t = Instant.ofEpochMilli(signalGeneratedAt).atZone(zone).plusNanos(horizonMs * 1_000_000L)
        while (!isTradingDay(t.dayOfWeek) || t.hour < 9 || (t.hour == 9 && t.minute < 40) || t.hour > 18 || (t.hour == 18 && t.minute > 10)) {
            t = t.plusDays(1).withHour(9).withMinute(40).withSecond(0).withNano(0)
            while (!isTradingDay(t.dayOfWeek)) t = t.plusDays(1)
        }
        return t.toInstant().toEpochMilli()
    }

    private fun isTradingDay(day: DayOfWeek): Boolean = day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY

    fun toleranceMs(name: String): Long = when (name) {
        "15m", "30m", "1h" -> 30 * 60_000L
        "4h" -> 90 * 60_000L
        "1d" -> 72 * 60 * 60_000L
        else -> 30 * 60_000L
    }

    fun match(candles: List<Candle>, targetTime: Long, toleranceMs: Long): Candle? =
        candles.asSequence()
            .filter { it.timestamp >= targetTime && it.timestamp <= targetTime + toleranceMs }
            .minByOrNull { it.timestamp }

    fun directionalReturnPct(signalPrice: Double, matchedPrice: Double, direction: String): Double? {
        if (!signalPrice.isFinite() || signalPrice <= 0.0 || !matchedPrice.isFinite() || matchedPrice <= 0.0) return null
        val raw = (matchedPrice / signalPrice - 1.0) * 100.0
        return if (direction.equals("SHORT", true)) -raw else raw
    }
}
