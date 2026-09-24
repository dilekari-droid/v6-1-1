package tr.borsatakip.v5.data.favorites

import android.content.Context

class BistWatchlistStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("bist_manual_watchlist", Context.MODE_PRIVATE)

    fun symbols(): List<String> = prefs.getStringSet(KEY, emptySet()).orEmpty()
        .map(FavoriteRepository::normalizeSymbol)
        .filter { it.matches(Regex("[A-Z0-9_]{3,12}")) }
        .distinct()
        .sorted()

    fun add(rawSymbol: String): AddResult {
        val symbol = FavoriteRepository.normalizeSymbol(rawSymbol)
        if (!symbol.matches(Regex("[A-Z0-9_]{3,12}"))) return AddResult.INVALID
        val current = symbols().toMutableSet()
        if (symbol in current) return AddResult.ALREADY_EXISTS
        if (current.size >= MAX_ITEMS) return AddResult.LIMIT_REACHED
        current += symbol
        prefs.edit().putStringSet(KEY, current).apply()
        return AddResult.ADDED
    }


    fun seedIfEmpty(candidates: Collection<String>): Int {
        if (symbols().isNotEmpty()) return 0
        val seeded = candidates
            .map(FavoriteRepository::normalizeSymbol)
            .filter { it.matches(Regex("[A-Z0-9_]{3,12}")) }
            .distinct()
            .take(MAX_ITEMS)
        if (seeded.isEmpty()) return 0
        prefs.edit().putStringSet(KEY, seeded.toSet()).apply()
        return seeded.size
    }

    fun remove(rawSymbol: String) {
        val symbol = FavoriteRepository.normalizeSymbol(rawSymbol)
        val current = symbols().toMutableSet()
        if (current.remove(symbol)) prefs.edit().putStringSet(KEY, current).apply()
    }

    enum class AddResult { ADDED, ALREADY_EXISTS, LIMIT_REACHED, INVALID }

    companion object {
        const val MAX_ITEMS = 20
        private const val KEY = "symbols"
    }
}
