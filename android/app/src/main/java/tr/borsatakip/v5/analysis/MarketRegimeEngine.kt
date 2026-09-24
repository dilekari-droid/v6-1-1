package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Candle
import kotlin.math.abs

/** Benchmark verisinden fail-closed piyasa rejimi üretir. Veri yetersizse UNKNOWN döner. */
object MarketRegimeEngine {
    enum class Regime(val label: String) {
        TREND_UP("TREND YUKARI"),
        TREND_DOWN("TREND AŞAĞI"),
        SIDEWAYS("YATAY"),
        HIGH_VOLATILITY("YÜKSEK VOLATİLİTE"),
        UNCERTAIN("BELİRSİZ"),
        UNKNOWN("VERİ YOK")
    }

    data class Result(
        val regime: Regime,
        val confidence: Int,
        val reason: String,
        val sampleCount: Int,
        val benchmarkSymbol: String
    )

    fun evaluate(
        benchmarkSymbol: String,
        input: List<Candle>
    ): Result {
        val c = OhlcvResampler.sanitize(input)
        if (c.size < 60) return Result(Regime.UNKNOWN, 0, "Benchmark için en az 60 geçerli mum gerekli.", c.size, benchmarkSymbol)
        val recent = c.takeLast(120)
        val trend = MultiTimeframeSignalAnalyzer.trend(recent)
        val t = TechnicalAnalyzer.analyze(recent)
        val last = recent.last().close
        val atrPct = t.atr14?.takeIf { it > 0.0 }?.let { it / last * 100.0 }
        val e20 = t.ema20
        val e50 = t.ema50
        val slope = trend.slopePct
        val r2 = trend.rSquared

        val regime = when {
            atrPct != null && atrPct >= 3.0 -> Regime.HIGH_VOLATILITY
            trend.direction == MultiTimeframeSignalAnalyzer.TrendDirection.UP && e20 != null && e50 != null && last >= e20 && e20 >= e50 -> Regime.TREND_UP
            trend.direction == MultiTimeframeSignalAnalyzer.TrendDirection.DOWN && e20 != null && e50 != null && last <= e20 && e20 <= e50 -> Regime.TREND_DOWN
            trend.direction == MultiTimeframeSignalAnalyzer.TrendDirection.SIDEWAYS && (r2 ?: 0.0) < 0.35 -> Regime.SIDEWAYS
            else -> Regime.UNCERTAIN
        }
        val confidence = when (regime) {
            Regime.UNKNOWN -> 0
            Regime.HIGH_VOLATILITY -> ((atrPct ?: 0.0) * 18.0).toInt().coerceIn(45, 95)
            Regime.TREND_UP, Regime.TREND_DOWN -> (((r2 ?: 0.0) * 70.0) + (abs(slope ?: 0.0) * 4.0)).toInt().coerceIn(35, 95)
            Regime.SIDEWAYS -> ((1.0 - (r2 ?: 0.0)) * 80.0).toInt().coerceIn(35, 90)
            Regime.UNCERTAIN -> 35
        }
        val reason = buildString {
            append("Benchmark $benchmarkSymbol • ${recent.size} mum")
            append(" • trend=${trend.direction.label}")
            slope?.let { append(" • eğim=${"%.2f".format(it)}%") }
            r2?.let { append(" • R²=${"%.2f".format(it)}") }
            atrPct?.let { append(" • ATR=${"%.2f".format(it)}%") }
        }
        return Result(regime, confidence, reason, recent.size, benchmarkSymbol)
    }

    fun rankingAdjustment(direction: String, result: Result): Int = when (result.regime) {
        Regime.TREND_UP -> if (direction.equals("LONG", true)) 6 else -6
        Regime.TREND_DOWN -> if (direction.equals("SHORT", true)) 6 else -6
        Regime.HIGH_VOLATILITY -> -4
        Regime.SIDEWAYS -> -2
        Regime.UNCERTAIN, Regime.UNKNOWN -> 0
    }
}
