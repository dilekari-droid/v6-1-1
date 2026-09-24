package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.ViopContract

enum class ViopContractCategory(val label: String) {
    ALL("Tümü"),
    BIST30("BIST30"),
    FX("Döviz"),
    COMMODITY("Emtia"),
    OTHER("Diğer")
}

/**
 * VİOP sözleşme ekranındaki kategori sınıflandırmasını tek noktada tutar.
 * Provider kayıtlarında ayrı kategori alanı bulunmadığı için dayanak/sembol kökü
 * üzerinden fail-safe sınıflandırma yapılır. Tanınmayan kayıtlar DİĞER'e gider.
 */
object ViopContractCategoryPolicy {
    private val bist30Roots = setOf(
        "AKBNK", "GARAN", "ASELS", "THYAO", "TUPRS", "ISCTR", "YKBNK", "KCHOL",
        "SAHOL", "EREGL", "SISE", "PGSUS", "BIMAS", "FROTO", "TOASO", "PETKM",
        "XU030"
    )

    private val fxTokens = listOf("USD", "EUR")
    private val commodityTokens = listOf("GOLD", "ALTIN", "SILVER", "GUMUS", "BRENT", "XAU", "XAG")

    fun classify(contract: ViopContract): ViopContractCategory {
        val underlying = ViopContractSearch.normalize(contract.underlying).replace(" ", "")
        val symbol = ViopContractSearch.normalize(contract.symbol).replace(" ", "")

        if (fxTokens.any { underlying.contains(it) || symbol.contains(it) }) {
            return ViopContractCategory.FX
        }
        if (commodityTokens.any { underlying.contains(it) || symbol.contains(it) }) {
            return ViopContractCategory.COMMODITY
        }
        if (underlying in bist30Roots || bist30Roots.any { symbol.contains(it) }) {
            return ViopContractCategory.BIST30
        }
        return ViopContractCategory.OTHER
    }

    fun filter(source: List<ViopContract>, category: ViopContractCategory): List<ViopContract> =
        if (category == ViopContractCategory.ALL) source
        else source.filter { classify(it) == category }
}
