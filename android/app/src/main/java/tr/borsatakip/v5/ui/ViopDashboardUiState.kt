package tr.borsatakip.v5.ui

/**
 * Pure UI state for the VIOP dashboard. This intentionally contains no market or signal data.
 * It is safe to persist across Activity recreation/reopen without changing scanner semantics.
 */
data class ViopDashboardUiState(
    val query: String = "",
    val category: Category = Category.ALL,
    val direction: Direction = Direction.ALL,
    val sort: Sort = Sort.RANKING,
    val advancedExpanded: Boolean = false
) {
    enum class Category { ALL, INDEX, EQUITY, FX, COMMODITY, RATE }
    enum class Direction { ALL, LONG, SHORT, WATCH }
    enum class Sort { RANKING, SIGNAL, CONFIDENCE }

    fun encode(): String = listOf(
        query.replace('\t', ' ').replace('\n', ' ').take(MAX_QUERY_LENGTH),
        category.name,
        direction.name,
        sort.name,
        if (advancedExpanded) "1" else "0"
    ).joinToString("\t")

    companion object {
        private const val MAX_QUERY_LENGTH = 80

        fun decode(raw: String?): ViopDashboardUiState {
            if (raw.isNullOrBlank()) return ViopDashboardUiState()
            val p = raw.split('\t')
            return ViopDashboardUiState(
                query = p.getOrNull(0).orEmpty().take(MAX_QUERY_LENGTH),
                category = enumOrDefault(p.getOrNull(1), Category.ALL),
                direction = enumOrDefault(p.getOrNull(2), Direction.ALL),
                sort = enumOrDefault(p.getOrNull(3), Sort.RANKING),
                advancedExpanded = p.getOrNull(4) == "1"
            )
        }

        private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == value } ?: default
    }
}
