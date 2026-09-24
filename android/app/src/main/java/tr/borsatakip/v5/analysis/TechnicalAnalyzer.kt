package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.TechnicalSnapshot
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

object TechnicalAnalyzer {
    const val MIN_COMPLETE_BARS = 200
    fun analyze(input: List<Candle>): TechnicalSnapshot {
        val c = input.filter { candle ->
            listOf(candle.open, candle.high, candle.low, candle.close, candle.volume).all { it.isFinite() } &&
                candle.high >= candle.low && candle.volume >= 0.0
        }
        if (c.size < MIN_COMPLETE_BARS) {
            return emptySnapshot(
                status = "INSUFFICIENT_DATA",
                reason = "Teknik analiz için en az $MIN_COMPLETE_BARS geçerli mum gerekli; ${c.size} mevcut.",
                availableBars = c.size
            )
        }

        val closes = c.map { it.close }
        val vols = c.map { it.volume }
        val e20 = ema(closes, 20).finiteOrNull()
        val e50 = ema(closes, 50).finiteOrNull()
        val e200 = ema(closes, 200).finiteOrNull()
        val rsi = rsiWilder(closes, 14).finiteOrNull()
        val macdLine = if (closes.size >= 26) {
            val fast = emaSeries(closes, 12)
            val slow = emaSeries(closes, 26)
            if (fast.isNotEmpty() && slow.isNotEmpty()) (fast.last() - slow.last()).finiteOrNull() else null
        } else null
        val macdSignal = if (closes.size >= 35) {
            val fast = emaSeries(closes, 12)
            val slow = emaSeries(closes, 26)
            val offset = fast.size - slow.size
            if (offset >= 0) {
                val line = slow.indices.map { fast[it + offset] - slow[it] }
                ema(line, 9).finiteOrNull()
            } else null
        } else null
        val bb = if (closes.size >= 20) {
            val w = closes.takeLast(20)
            val m = w.average()
            val variance = w.sumOf { (it - m).pow(2) } / w.size
            val sd = sqrt(variance.coerceAtLeast(0.0))
            Pair((m + 2 * sd).finiteOrNull(), (m - 2 * sd).finiteOrNull())
        } else null
        val atr = atr(c, 14).finiteOrNull()
        // Günlük/çok dönemli hacim ağırlıklı ortalama ile gerçek intraday Session VWAP ayrı tutulur.
        val vwmaWindow = c.takeLast(20)
        val totalVolume = vwmaWindow.sumOf { it.volume }
        val vwma20 = if (vwmaWindow.isNotEmpty() && totalVolume > 0.0 && totalVolume.isFinite()) {
            (vwmaWindow.sumOf { ((it.high + it.low + it.close) / 3.0) * it.volume } / totalVolume).finiteOrNull()
        } else null
        val sessionVwap = sessionVwap(c)
        val vr = if (vols.size >= 21) {
            val base = vols.dropLast(1).takeLast(20).average()
            if (base > 0.0 && base.isFinite()) (vols.last() / base).finiteOrNull() else null
        } else null
        // Structural support/resistance must precede the signal candle; otherwise the newest
        // low/high can incorrectly confirm itself as its own support/resistance.
        val structureWindow = c.dropLast(1).takeLast(20)
        val lows = structureWindow.map { it.low }
        val highs = structureWindow.map { it.high }
        return TechnicalSnapshot(
            e20, e50, e200, rsi, macdLine, macdSignal,
            bb?.first, bb?.second, atr, vwma20, vr,
            lows.minOrNull().finiteOrNull(), highs.maxOrNull().finiteOrNull(),
            vwma20 = vwma20, sessionVwap = sessionVwap,
            analysisStatus = "OK", availableBars = c.size, requiredBars = MIN_COMPLETE_BARS
        )
    }

    /** Gerçek Session VWAP yalnız intraday seri olduğunda, son İstanbul takvim günündeki mumlardan hesaplanır. */
    fun sessionVwap(input: List<Candle>): Double? {
        val c = input.sortedBy { it.timestamp }
        if (c.size < 2) return null
        val deltas = c.zipWithNext { a, b -> b.timestamp - a.timestamp }.filter { it > 0L }.sorted()
        val medianDelta = deltas.getOrNull(deltas.size / 2) ?: return null
        if (medianDelta >= 6L * 60L * 60L * 1000L) return null
        val zone = java.time.ZoneId.of("Europe/Istanbul")
        val lastDate = java.time.Instant.ofEpochMilli(c.last().timestamp).atZone(zone).toLocalDate()
        val session = c.filter { java.time.Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() == lastDate }
        if (session.size < 2) return null
        val vol = session.sumOf { it.volume }
        if (!vol.isFinite() || vol <= 0.0) return null
        return (session.sumOf { ((it.high + it.low + it.close) / 3.0) * it.volume } / vol).finiteOrNull()
    }

    private fun emptySnapshot(
        status: String = "INSUFFICIENT_DATA",
        reason: String = "Teknik analiz için yeterli veri yok.",
        availableBars: Int = 0
    ) = TechnicalSnapshot(
        null, null, null, null, null, null, null, null,
        null, null, null, null, null,
        analysisStatus = status, insufficientReason = reason,
        availableBars = availableBars, requiredBars = MIN_COMPLETE_BARS
    )

    private fun ema(values: List<Double>, period: Int): Double? =
        if (values.size < period) null else emaSeries(values, period).lastOrNull()

    private fun emaSeries(values: List<Double>, period: Int): List<Double> {
        if (values.size < period || period <= 0) return emptyList()
        val seed = values.take(period).average()
        if (!seed.isFinite()) return emptyList()
        val out = mutableListOf<Double>()
        var e = seed
        out += e
        val k = 2.0 / (period + 1)
        for (i in period until values.size) {
            val x = values[i]
            if (!x.isFinite()) return emptyList()
            e = x * k + e * (1 - k)
            if (!e.isFinite()) return emptyList()
            out += e
        }
        return out
    }

    private fun rsiWilder(v: List<Double>, p: Int): Double? {
        if (p <= 0 || v.size < p + 1) return null
        val d = (1 until v.size).map { v[it] - v[it - 1] }
        var g = d.take(p).sumOf { if (it > 0) it else 0.0 } / p
        var l = d.take(p).sumOf { if (it < 0) -it else 0.0 } / p
        for (i in p until d.size) {
            val x = d[i]
            g = (g * (p - 1) + (if (x > 0) x else 0.0)) / p
            l = (l * (p - 1) + (if (x < 0) -x else 0.0)) / p
        }
        if (!g.isFinite() || !l.isFinite()) return null
        if (g == 0.0 && l == 0.0) return 50.0
        if (l == 0.0) return 100.0
        val rs = g / l
        if (!rs.isFinite()) return null
        return 100 - (100 / (1 + rs))
    }

    private fun atr(c: List<Candle>, p: Int): Double? {
        if (p <= 0 || c.size < p + 1) return null
        val tr = (1 until c.size).map { i ->
            maxOf(
                c[i].high - c[i].low,
                abs(c[i].high - c[i - 1].close),
                abs(c[i].low - c[i - 1].close)
            )
        }
        if (tr.size < p) return null
        var a = tr.take(p).average()
        if (!a.isFinite()) return null
        for (i in p until tr.size) {
            a = (a * (p - 1) + tr[i]) / p
            if (!a.isFinite()) return null
        }
        return a
    }

    private fun Double?.finiteOrNull(): Double? = this?.takeIf { it.isFinite() }
}
