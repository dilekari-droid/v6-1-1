package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object LinearTrendAnalyzer {
    enum class Direction(val label: String) { UP("YÜKSELİŞ"), FLAT("YATAY"), DOWN("DÜŞÜŞ") }
    enum class Strength(val label: String) { VERY_STRONG("ÇOK GÜÇLÜ"), STRONG("GÜÇLÜ"), MEDIUM("ORTA"), WEAK("ZAYIF") }
    enum class Breakout(val label: String) { UP("YUKARI KIRILIM"), DOWN("AŞAĞI KIRILIM"), NONE("KIRILIM YOK"), WAITING("TEYİT BEKLENİYOR") }
    enum class VolumeConfirmation(val label: String) { STRONG("GÜÇLÜ"), WEAK("ZAYIF"), NORMAL("NORMAL"), UNAVAILABLE("HACİM TEYİDİ YOK") }

    data class RegressionLine(
        val slope: Double,
        val intercept: Double,
        val r2: Double,
        val contactCount: Int,
        val lastContactIndex: Int?,
        val direction: Direction,
        val strength: Strength,
        val strengthScore: Int,
        val sourceIndices: List<Int>
    ) {
        fun valueAt(index: Int): Double = intercept + slope * index.toDouble()
    }

    data class Result(
        val candleCount: Int,
        val main: RegressionLine?,
        val support: RegressionLine?,
        val resistance: RegressionLine?,
        val supportNow: Double?,
        val resistanceNow: Double?,
        val breakout: Breakout,
        val breakoutPct: Double?,
        val volumeConfirmation: VolumeConfirmation,
        val message: String? = null
    )

    fun analyze(input: List<Candle>): Result {
        val candles = OhlcvResampler.sanitize(input)
        if (candles.size < MIN_CANDLES) {
            return Result(candles.size, null, null, null, null, null, Breakout.NONE, null, VolumeConfirmation.UNAVAILABLE, "Trend analizi için yeterli veri yok.")
        }

        val main = regression(candles.indices.toList(), candles.map { it.close }, candles)
        val lows = swingIndices(candles, low = true)
        val highs = swingIndices(candles, low = false)
        val supportCandidate = regression(lows, lows.map { candles[it].low }, candles)
        val resistanceCandidate = regression(highs, highs.map { candles[it].high }, candles)
        val support = supportCandidate?.takeIf { it.slope > slopeEpsilon(candles) }
        val resistance = resistanceCandidate?.takeIf { it.slope < -slopeEpsilon(candles) }
        val last = candles.lastIndex
        val supportNow = support?.valueAt(last)?.takeIf { it.isFinite() && it > 0.0 }
        val resistanceNow = resistance?.valueAt(last)?.takeIf { it.isFinite() && it > 0.0 }
        val breakout = detectBreakout(candles, support, resistance)
        return Result(
            candleCount = candles.size,
            main = main,
            support = support,
            resistance = resistance,
            supportNow = supportNow,
            resistanceNow = resistanceNow,
            breakout = breakout.status,
            breakoutPct = breakout.pct,
            volumeConfirmation = breakout.volume,
            message = null
        )
    }

    private data class BreakoutResult(val status: Breakout, val pct: Double?, val volume: VolumeConfirmation)

    private fun detectBreakout(c: List<Candle>, support: RegressionLine?, resistance: RegressionLine?): BreakoutResult {
        if (c.size < 3) return BreakoutResult(Breakout.NONE, null, VolumeConfirmation.UNAVAILABLE)
        val last = c.lastIndex
        val atr = averageRange(c)
        val tolerancePct = max(MIN_BREAK_PCT, ((atr / c.last().close) * ATR_BREAK_FRACTION).coerceAtMost(MAX_BREAK_PCT))

        fun pct(close: Double, line: Double): Double = if (line > 0.0) ((close - line) / line) * 100.0 else 0.0
        fun volumeState(index: Int): VolumeConfirmation {
            val base = c.subList(max(0, index - 20), index).map { it.volume }.filter { it > 0.0 }
            if (base.size < 5 || c[index].volume <= 0.0) return VolumeConfirmation.UNAVAILABLE
            val avg = base.average()
            if (!avg.isFinite() || avg <= 0.0) return VolumeConfirmation.UNAVAILABLE
            val ratio = c[index].volume / avg
            return when {
                ratio >= 1.35 -> VolumeConfirmation.STRONG
                ratio < 0.85 -> VolumeConfirmation.WEAK
                else -> VolumeConfirmation.NORMAL
            }
        }

        resistance?.let { line ->
            val currentLine = line.valueAt(last)
            val prevLine = line.valueAt(last - 1)
            val currentBreak = currentLine > 0 && c[last].close > currentLine * (1.0 + tolerancePct)
            val prevInside = prevLine > 0 && c[last - 1].close <= prevLine * (1.0 + tolerancePct * 0.5)
            if (currentBreak && prevInside) return BreakoutResult(Breakout.WAITING, pct(c[last].close, currentLine), volumeState(last))
            val p2Line = line.valueAt(last - 2)
            val prevBreak = prevLine > 0 && c[last - 1].close > prevLine * (1.0 + tolerancePct)
            val beforeInside = p2Line > 0 && c[last - 2].close <= p2Line * (1.0 + tolerancePct * 0.5)
            val confirmed = currentLine > 0 && c[last].close > currentLine * (1.0 + tolerancePct * 0.5)
            if (prevBreak && beforeInside && confirmed) return BreakoutResult(Breakout.UP, pct(c[last].close, currentLine), volumeState(last - 1))
        }

        support?.let { line ->
            val currentLine = line.valueAt(last)
            val prevLine = line.valueAt(last - 1)
            val currentBreak = currentLine > 0 && c[last].close < currentLine * (1.0 - tolerancePct)
            val prevInside = prevLine > 0 && c[last - 1].close >= prevLine * (1.0 - tolerancePct * 0.5)
            if (currentBreak && prevInside) return BreakoutResult(Breakout.WAITING, pct(c[last].close, currentLine), volumeState(last))
            val p2Line = line.valueAt(last - 2)
            val prevBreak = prevLine > 0 && c[last - 1].close < prevLine * (1.0 - tolerancePct)
            val beforeInside = p2Line > 0 && c[last - 2].close >= p2Line * (1.0 - tolerancePct * 0.5)
            val confirmed = currentLine > 0 && c[last].close < currentLine * (1.0 - tolerancePct * 0.5)
            if (prevBreak && beforeInside && confirmed) return BreakoutResult(Breakout.DOWN, pct(c[last].close, currentLine), volumeState(last - 1))
        }
        return BreakoutResult(Breakout.NONE, null, VolumeConfirmation.UNAVAILABLE)
    }

    private fun regression(indices: List<Int>, values: List<Double>, all: List<Candle>): RegressionLine? {
        if (indices.size < 2 || indices.size != values.size) return null
        if (values.any { !it.isFinite() || it <= 0.0 }) return null
        val xMean = indices.average()
        val yMean = values.average()
        var sxx = 0.0
        var sxy = 0.0
        for (i in indices.indices) {
            val dx = indices[i] - xMean
            sxx += dx * dx
            sxy += dx * (values[i] - yMean)
        }
        if (sxx <= 0.0 || !sxx.isFinite()) return null
        val slope = sxy / sxx
        val intercept = yMean - slope * xMean
        if (!slope.isFinite() || !intercept.isFinite()) return null
        val ssTot = values.sumOf { (it - yMean).pow(2) }
        val ssRes = values.indices.sumOf { i -> (values[i] - (intercept + slope * indices[i])).pow(2) }
        val r2 = if (ssTot <= 1e-12) 1.0 else (1.0 - ssRes / ssTot).coerceIn(0.0, 1.0)
        val contacts = contacts(all, indices, values, slope, intercept)
        val direction = classifyDirection(slope, yMean, indices.maxOrNull() ?: 0, indices.minOrNull() ?: 0)
        val strength = classifyStrength(r2, contacts.first, contacts.second, all.lastIndex)
        return RegressionLine(slope, intercept, r2, contacts.first, contacts.second, direction, strength.second, strength.first, indices)
    }

    private fun contacts(all: List<Candle>, indices: List<Int>, values: List<Double>, slope: Double, intercept: Double): Pair<Int, Int?> {
        val avgRange = averageRange(all)
        var count = 0
        var last: Int? = null
        indices.forEachIndexed { k, idx ->
            val expected = intercept + slope * idx
            val tolerance = max(expected * CONTACT_TOLERANCE_PCT, avgRange * 0.20)
            if (abs(values[k] - expected) <= tolerance) { count++; last = idx }
        }
        return count to last
    }

    private fun classifyStrength(r2: Double, contacts: Int, lastContact: Int?, lastIndex: Int): Pair<Int, Strength> {
        val r2Score = (r2 * 50.0).coerceIn(0.0, 50.0)
        val contactScore = min(30.0, contacts * 6.0)
        val recency = if (lastContact == null || lastIndex <= 0) 0.0 else {
            val barsAgo = (lastIndex - lastContact).coerceAtLeast(0)
            (20.0 * (1.0 - barsAgo.toDouble() / max(1, lastIndex).toDouble())).coerceIn(0.0, 20.0)
        }
        val score = (r2Score + contactScore + recency).toInt().coerceIn(0, 100)
        val strength = when {
            score >= 80 -> Strength.VERY_STRONG
            score >= 65 -> Strength.STRONG
            score >= 45 -> Strength.MEDIUM
            else -> Strength.WEAK
        }
        return score to strength
    }

    private fun classifyDirection(slope: Double, meanPrice: Double, maxIndex: Int, minIndex: Int): Direction {
        if (meanPrice <= 0.0) return Direction.FLAT
        val span = max(1, maxIndex - minIndex)
        val totalPct = slope * span / meanPrice * 100.0
        return when {
            totalPct > FLAT_TOTAL_PCT -> Direction.UP
            totalPct < -FLAT_TOTAL_PCT -> Direction.DOWN
            else -> Direction.FLAT
        }
    }

    private fun swingIndices(c: List<Candle>, low: Boolean): List<Int> {
        if (c.size < 7) return emptyList()
        val avgRange = averageRange(c)
        val result = mutableListOf<Int>()
        for (i in SWING_RADIUS until c.size - SWING_RADIUS) {
            val value = if (low) c[i].low else c[i].high
            val neighborhood = (i - SWING_RADIUS..i + SWING_RADIUS).filter { it != i }.map { if (low) c[it].low else c[it].high }
            val extreme = if (low) value <= neighborhood.minOrNull()!! else value >= neighborhood.maxOrNull()!!
            if (!extreme) continue
            val neighborMean = neighborhood.average()
            val prominence = if (low) neighborMean - value else value - neighborMean
            if (prominence >= max(avgRange * 0.15, value * 0.0005)) result += i
        }
        return result
    }

    private fun averageRange(c: List<Candle>): Double {
        val ranges = c.takeLast(min(30, c.size)).map { (it.high - it.low).coerceAtLeast(0.0) }.filter { it.isFinite() }
        return ranges.average().takeIf { it.isFinite() } ?: 0.0
    }

    private fun slopeEpsilon(c: List<Candle>): Double {
        val mean = c.map { it.close }.average().takeIf { it.isFinite() && it > 0.0 } ?: return 0.0
        return mean * 0.00002
    }

    private const val MIN_CANDLES = 12
    private const val SWING_RADIUS = 2
    private const val CONTACT_TOLERANCE_PCT = 0.0025
    private const val FLAT_TOTAL_PCT = 0.8
    private const val MIN_BREAK_PCT = 0.003
    private const val MAX_BREAK_PCT = 0.02
    private const val ATR_BREAK_FRACTION = 0.25
}
