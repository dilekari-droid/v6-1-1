package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.TechnicalSnapshot

enum class TrendUiState {
    STRONG_BULLISH,
    BULLISH,
    NEUTRAL,
    BEARISH,
    STRONG_BEARISH,
    INSUFFICIENT_DATA,
    REJECTED
}

data class TrendUiStyle(
    val state: TrendUiState,
    val label: String,
    val arrow: String,
    val red: Int,
    val green: Int,
    val blue: Int
)

/**
 * Maps the selected analysis-timeframe technical snapshot to one consistent trend state.
 * Daily price change is deliberately NOT used: card colour must represent technical trend,
 * not a one-day price movement. Signal direction also remains a separate concept.
 */
object TrendUiPolicy {
    fun resolve(opportunity: Opportunity): TrendUiStyle = resolve(
        price = opportunity.price,
        technical = opportunity.technical,
        decisionState = opportunity.decisionState
    )

    fun resolve(
        price: Double,
        technical: TechnicalSnapshot,
        decisionState: DecisionState
    ): TrendUiStyle {
        if (decisionState == DecisionState.REJECTED) return style(TrendUiState.REJECTED)
        if (decisionState == DecisionState.INSUFFICIENT_DATA) return style(TrendUiState.INSUFFICIENT_DATA)
        if (!price.isFinite() || price <= 0.0) return style(TrendUiState.INSUFFICIENT_DATA)

        val ema20 = technical.ema20?.takeIf { it.isFinite() && it > 0.0 }
        val ema50 = technical.ema50?.takeIf { it.isFinite() && it > 0.0 }
        val ema200 = technical.ema200?.takeIf { it.isFinite() && it > 0.0 }
        if (ema20 == null || ema50 == null) return style(TrendUiState.INSUFFICIENT_DATA)

        val rsi = technical.rsi14?.takeIf { it.isFinite() }
        val macd = technical.macd?.takeIf { it.isFinite() }
        val macdSignal = technical.macdSignal?.takeIf { it.isFinite() }
        val macdBull = macd != null && macdSignal != null && macd > macdSignal
        val macdBear = macd != null && macdSignal != null && macd < macdSignal
        val rsiBull = rsi != null && rsi >= 52.0
        val rsiBear = rsi != null && rsi <= 48.0

        val bullish = price > ema20 && ema20 > ema50
        val bearish = price < ema20 && ema20 < ema50
        val strongBullish = bullish && ema200 != null && ema50 > ema200 && macdBull && rsiBull
        val strongBearish = bearish && ema200 != null && ema50 < ema200 && macdBear && rsiBear

        return style(
            when {
                strongBullish -> TrendUiState.STRONG_BULLISH
                strongBearish -> TrendUiState.STRONG_BEARISH
                bullish -> TrendUiState.BULLISH
                bearish -> TrendUiState.BEARISH
                else -> TrendUiState.NEUTRAL
            }
        )
    }

    private fun style(state: TrendUiState): TrendUiStyle = when (state) {
        TrendUiState.STRONG_BULLISH -> TrendUiStyle(state, "GÜÇLÜ YÜKSELİŞ", "↑", 11, 110, 79)
        TrendUiState.BULLISH -> TrendUiStyle(state, "YÜKSELİŞ", "↑", 22, 163, 74)
        TrendUiState.NEUTRAL -> TrendUiStyle(state, "YATAY", "→", 107, 114, 128)
        TrendUiState.BEARISH -> TrendUiStyle(state, "DÜŞÜŞ", "↓", 220, 38, 38)
        TrendUiState.STRONG_BEARISH -> TrendUiStyle(state, "GÜÇLÜ DÜŞÜŞ", "↓", 153, 27, 27)
        TrendUiState.INSUFFICIENT_DATA -> TrendUiStyle(state, "YETERSİZ VERİ", "—", 107, 114, 128)
        TrendUiState.REJECTED -> TrendUiStyle(state, "REDDEDİLDİ", "—", 75, 85, 99)
    }
}
