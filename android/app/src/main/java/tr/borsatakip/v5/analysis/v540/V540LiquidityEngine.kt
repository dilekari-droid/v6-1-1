package tr.borsatakip.v5.analysis.v540

import kotlin.math.roundToInt

/**
 * Relative liquidity evidence. V5.4.5 prefers rolling distribution evidence when available.
 * Absolute volume/OI thresholds are deliberately avoided.
 */
object V540LiquidityEngine {
    fun score(volumeRatio: Double?, spreadPct: Double?): Int? = score(volumeRatio, spreadPct, null, null)

    fun score(
        volumeRatio: Double?,
        spreadPct: Double?,
        currentVolume: Double?,
        historicalComparableVolumes: List<Double>?
    ): Int? {
        val volume = volumeRatio?.takeIf { it.isFinite() && it >= 0.0 }
        val spread = spreadPct?.takeIf { it.isFinite() && it >= 0.0 }
        val hist = historicalComparableVolumes.orEmpty().filter { it.isFinite() && it >= 0.0 }.sorted()
        val current = currentVolume?.takeIf { it.isFinite() && it >= 0.0 }
        val percentile = if (current != null && hist.size >= 20) {
            val lessOrEqual = hist.count { it <= current }
            lessOrEqual * 100.0 / hist.size
        } else null
        val medianRelative = if (current != null && hist.size >= 20) {
            val median = if (hist.size % 2 == 1) hist[hist.size/2] else (hist[hist.size/2-1] + hist[hist.size/2]) / 2.0
            if (median > 0.0) current / median else null
        } else null

        val parts = mutableListOf<Pair<Double, Double>>()
        percentile?.let { parts += it.coerceIn(0.0,100.0) to 0.35 }
        medianRelative?.let {
            val q=(it/(it+1.0)*100.0).coerceIn(0.0,100.0)
            parts += q to 0.20
        }
        volume?.let {
            val q=(it/(it+1.0)*100.0).coerceIn(0.0,100.0)
            parts += q to if (percentile != null) 0.20 else 0.60
        }
        spread?.let {
            val q=(100.0-(it/0.50*100.0)).coerceIn(0.0,100.0)
            parts += q to if (percentile != null) 0.25 else 0.40
        }
        val w=parts.sumOf{it.second}
        return if(w<=0.0) null else (parts.sumOf{it.first*it.second}/w).roundToInt().coerceIn(0,100)
    }
}
