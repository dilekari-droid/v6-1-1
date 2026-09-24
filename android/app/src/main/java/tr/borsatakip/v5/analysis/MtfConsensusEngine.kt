package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Candle

object MtfConsensusEngine {
    data class Input(val label: String, val candles: List<Candle>, val weight: Double)
    data class Result(
        val label: String,
        val score: Int,
        val availableCount: Int,
        val bullishWeight: Double,
        val bearishWeight: Double,
        val neutralWeight: Double,
        val explanation: String
    )

    fun evaluate(inputs: List<Input>): Result {
        var up = 0.0
        var down = 0.0
        var neutral = 0.0
        var available = 0
        val evidence = mutableListOf<String>()
        inputs.forEach { x ->
            val r = MultiTimeframeSignalAnalyzer.trend(x.candles)
            if (r.direction == MultiTimeframeSignalAnalyzer.TrendDirection.INSUFFICIENT) return@forEach
            available++
            when (r.direction) {
                MultiTimeframeSignalAnalyzer.TrendDirection.UP -> up += x.weight
                MultiTimeframeSignalAnalyzer.TrendDirection.DOWN -> down += x.weight
                MultiTimeframeSignalAnalyzer.TrendDirection.SIDEWAYS -> neutral += x.weight
                else -> Unit
            }
            evidence += "${x.label}:${r.direction.label}"
        }
        if (available < 3) return Result("YETERSİZ MTF", 0, available, up, down, neutral, evidence.joinToString(" • "))
        val directional = up + down + neutral
        if (directional <= 0.0) return Result("YETERSİZ MTF", 0, available, up, down, neutral, evidence.joinToString(" • "))
        val raw = ((up - down) / directional * 100.0).toInt().coerceIn(-100, 100)
        val label = when {
            raw >= 55 -> "GÜÇLÜ LONG KONSENSÜS"
            raw >= 20 -> "LONG KONSENSÜS"
            raw <= -55 -> "GÜÇLÜ SHORT KONSENSÜS"
            raw <= -20 -> "SHORT KONSENSÜS"
            else -> "KARIŞIK / NÖTR"
        }
        return Result(label, raw, available, up, down, neutral, evidence.joinToString(" • "))
    }

    fun rankingAdjustment(direction: String, result: Result): Int {
        if (result.availableCount < 3) return 0
        return when {
            direction.equals("LONG", true) && result.score >= 55 -> 8
            direction.equals("LONG", true) && result.score >= 20 -> 4
            direction.equals("LONG", true) && result.score <= -55 -> -8
            direction.equals("LONG", true) && result.score <= -20 -> -4
            direction.equals("SHORT", true) && result.score <= -55 -> 8
            direction.equals("SHORT", true) && result.score <= -20 -> 4
            direction.equals("SHORT", true) && result.score >= 55 -> -8
            direction.equals("SHORT", true) && result.score >= 20 -> -4
            else -> 0
        }
    }
}
