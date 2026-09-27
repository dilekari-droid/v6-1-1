package tr.borsatakip.v5.ui

import tr.borsatakip.v5.model.Opportunity

enum class DiscoverySort {
    TREND_STRENGTH,
    CHANGE,
    VOLUME,
    PRICE
}

/**
 * Fırsat Kontrolü sıralama sözleşmesi.
 * Yalnız gerçek model alanlarını kullanır; UI katmanında sahte finansal değer üretmez.
 */
object OpportunitySortPolicy {
    val defaultSort: DiscoverySort = DiscoverySort.TREND_STRENGTH

    fun sort(items: List<Opportunity>, mode: DiscoverySort): List<Opportunity> = when (mode) {
        DiscoverySort.TREND_STRENGTH -> items.sortedWith(
            compareByDescending<Opportunity> { OpportunityUiPolicy.strength(it) ?: -1 }
                .thenByDescending { it.finalSignalScore }
                .thenBy { it.symbol }
        )
        DiscoverySort.CHANGE -> items.sortedWith(
            compareByDescending<Opportunity> { it.dailyChangePct ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.symbol }
        )
        DiscoverySort.VOLUME -> items.sortedWith(
            compareByDescending<Opportunity> {
                it.candles.lastOrNull()?.volume?.takeIf { v -> v.isFinite() && v >= 0.0 }
                    ?: Double.NEGATIVE_INFINITY
            }.thenBy { it.symbol }
        )
        DiscoverySort.PRICE -> items.sortedWith(
            compareByDescending<Opportunity> { it.price.takeIf(Double::isFinite) ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.symbol }
        )
    }

    fun label(mode: DiscoverySort): String = when (mode) {
        DiscoverySort.TREND_STRENGTH -> "Trend Gücü"
        DiscoverySort.CHANGE -> "Değişim"
        DiscoverySort.VOLUME -> "Hacim"
        DiscoverySort.PRICE -> "Fiyat"
    }

    fun next(mode: DiscoverySort): DiscoverySort = when (mode) {
        DiscoverySort.TREND_STRENGTH -> DiscoverySort.CHANGE
        DiscoverySort.CHANGE -> DiscoverySort.VOLUME
        DiscoverySort.VOLUME -> DiscoverySort.PRICE
        DiscoverySort.PRICE -> DiscoverySort.TREND_STRENGTH
    }
}
