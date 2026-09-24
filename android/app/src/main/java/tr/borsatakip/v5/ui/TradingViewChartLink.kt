package tr.borsatakip.v5.ui

import java.util.Locale

/** Builds the TradingView symbol used only for external chart navigation. */
internal object TradingViewChartLink {
    private val bistTicker = Regex("[A-Z0-9_]{2,12}")

    fun toBistSymbol(rawSymbol: String): String? {
        val ticker = rawSymbol
            .trim()
            .uppercase(Locale.ROOT)
            .removePrefix("BIST:")
            .removeSuffix(".IS")
            .trim()
        if (!bistTicker.matches(ticker)) return null
        return "BIST:$ticker"
    }
}
