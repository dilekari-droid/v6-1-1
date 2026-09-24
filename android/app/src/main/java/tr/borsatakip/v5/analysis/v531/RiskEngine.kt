package tr.borsatakip.v5.analysis.v531

import kotlin.math.roundToInt

object RiskEngine {
    data class Input(
        val price: Double,
        val atr: Double?,
        val spreadPct: Double?,
        val liquidityScore: Int?,
        val dataConfidence: Int,
        val mtfConflictSeverity: Int?,
        val structureDistanceAtr: Double?,
        val slippageSensitivity: Double?,
        val contractRiskPct: Int?
    )

    fun evaluate(input: Input): V531RiskResult {
        val factors = linkedMapOf<String, Pair<Int, Int>>()
        val reasons = mutableListOf<V531ReasonCode>()

        val atrPct = input.atr?.takeIf { it.isFinite() && it > 0.0 && input.price > 0.0 }?.let { it / input.price * 100.0 }
        if (atrPct != null) {
            val risk = when {
                atrPct <= 1.5 -> 10
                atrPct <= 2.5 -> 25
                atrPct <= 4.0 -> 55
                atrPct <= 6.0 -> 80
                else -> 100
            }
            factors["VOLATILITY"] = risk to 20
            if (risk >= 80) reasons += V531ReasonCode.HIGH_VOLATILITY
        }
        input.spreadPct?.takeIf { it.isFinite() && it >= 0.0 }?.let {
            val risk = when {
                it <= 0.05 -> 5
                it <= 0.10 -> 15
                it <= 0.25 -> 40
                it <= 0.50 -> 70
                else -> 100
            }
            factors["SPREAD"] = risk to 15
        }
        input.liquidityScore?.let { factors["LIQUIDITY"] = (100 - it.coerceIn(0, 100)) to 20 }
        factors["DATA_QUALITY"] = (100 - input.dataConfidence.coerceIn(0, 100)) to 20
        input.mtfConflictSeverity?.let { factors["TIMEFRAME_CONFLICT"] = it.coerceIn(0, 100) to 10 }
        // structureDistanceAtr means distance to the nearest structural barrier.
        // A nearby barrier constrains trade room and is therefore higher immediate risk.
        input.structureDistanceAtr?.takeIf { it.isFinite() && it >= 0.0 }?.let { distanceAtr ->
            val normalizedRoom = (distanceAtr / 4.0).coerceIn(0.0, 1.0)
            val risk = ((1.0 - normalizedRoom) * 100.0).roundToInt().coerceIn(0, 100)
            factors["NEAREST_BARRIER_DISTANCE"] = risk to 5
        }
        input.slippageSensitivity?.takeIf { it.isFinite() }?.let {
            factors["SLIPPAGE"] = (it.coerceIn(0.0, 1.0) * 100.0).roundToInt() to 5
        }
        input.contractRiskPct?.let { factors["CONTRACT"] = it.coerceIn(0, 100) to 5 }

        val availableWeight = factors.values.sumOf { it.second }
        val score = if (availableWeight > 0) {
            (factors.values.sumOf { it.first * it.second }.toDouble() / availableWeight).roundToInt().coerceIn(0, 100)
        } else 100
        val coverage = availableWeight.coerceIn(0, 100)
        val level = when {
            coverage < 40 -> V531RiskLevel.UNKNOWN
            score >= 70 -> V531RiskLevel.HIGH
            score >= 40 -> V531RiskLevel.MEDIUM
            else -> V531RiskLevel.LOW
        }
        return V531RiskResult(score, level, coverage, factors.mapValues { it.value.first }, reasons.distinct())
    }
}
