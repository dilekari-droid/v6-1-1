package tr.borsatakip.v5.analysis

import kotlin.math.max

object StrategyComparisonMath {
    fun profitFactor(returns: List<Double>): Double? {
        val valid = returns.filter { it.isFinite() }
        if (valid.isEmpty()) return null
        val grossProfit = valid.filter { it > 0.0 }.sum()
        val grossLoss = -valid.filter { it < 0.0 }.sum()
        return when {
            grossLoss > 0.0 -> grossProfit / grossLoss
            grossProfit > 0.0 -> Double.POSITIVE_INFINITY
            else -> null
        }
    }

    /** Compounded equity drawdown; does not assume additive percentage returns. */
    fun maxDrawdownPct(returns: List<Double>): Double? {
        val valid = returns.filter { it.isFinite() }
        if (valid.isEmpty()) return null
        var equity = 100.0
        var peak = equity
        var maxDd = 0.0
        for (r in valid) {
            equity *= (1.0 + r / 100.0).coerceAtLeast(0.0)
            peak = max(peak, equity)
            if (peak > 0.0) maxDd = max(maxDd, (peak - equity) / peak * 100.0)
        }
        return maxDd
    }

    fun confidenceLabel(sampleCount: Int): String = when {
        sampleCount >= 100 -> "YÜKSEK"
        sampleCount >= 50 -> "ORTA"
        sampleCount >= 20 -> "SINIRLI"
        else -> "YETERSİZ ÖRNEK"
    }
}
