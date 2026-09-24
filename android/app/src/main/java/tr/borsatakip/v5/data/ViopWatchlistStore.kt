package tr.borsatakip.v5.data

import android.content.Context
import tr.borsatakip.v5.analysis.ViopContractCategory
import java.util.Locale

/**
 * Persistent, category-scoped VİOP watchlists.
 *
 * Only contract symbols are persisted. Market/quote fields are always joined with
 * the latest contract universe, so cached UI values are never treated as fresh data.
 * Every visible VİOP category owns an independent 20-item list.
 */
class ViopWatchlistStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun symbols(category: ViopContractCategory): List<String> = prefs
        .getStringSet(categoryKey(category), emptySet()).orEmpty()
        .map(::normalizeSymbol)
        .filter(::isValidSymbol)
        .distinct()
        .sorted()

    /** Backward-compatible aggregate view; new UI must use symbols(category). */
    fun symbols(): List<String> = if (isCategoryMigrationComplete()) {
        WATCHLIST_CATEGORIES.flatMap(::symbols).distinct().sorted()
    } else {
        legacySymbols()
    }

    fun add(category: ViopContractCategory, rawSymbol: String): AddResult {
        if (category !in WATCHLIST_CATEGORIES) return AddResult.INVALID
        val mutation = addTo(symbols(category), rawSymbol)
        if (mutation.result == AddResult.ADDED) {
            prefs.edit().putStringSet(categoryKey(category), mutation.symbols.toSet()).apply()
        }
        return mutation.result
    }


    fun seedIfEmpty(category: ViopContractCategory, rawSymbols: Collection<String>): Int {
        if (category !in WATCHLIST_CATEGORIES || symbols(category).isNotEmpty()) return 0
        val seeded = rawSymbols.map(::normalizeSymbol).filter(::isValidSymbol).distinct().take(MAX_ITEMS)
        if (seeded.isEmpty()) return 0
        prefs.edit().putStringSet(categoryKey(category), seeded.toSet()).apply()
        return seeded.size
    }

    fun remove(category: ViopContractCategory, rawSymbol: String) {
        if (category !in WATCHLIST_CATEGORIES) return
        val next = removeFrom(symbols(category), rawSymbol)
        prefs.edit().putStringSet(categoryKey(category), next.toSet()).apply()
    }

    fun legacySymbols(): List<String> = prefs.getStringSet(LEGACY_KEY, emptySet()).orEmpty()
        .map(::normalizeSymbol)
        .filter(::isValidSymbol)
        .distinct()
        .sorted()

    fun isCategoryMigrationComplete(): Boolean = prefs.getBoolean(KEY_CATEGORY_MIGRATED, false)

    /**
     * Migrates the old single VİOP list once. Existing category data is merged,
     * never overwritten. The legacy key is intentionally retained for rollback.
     */
    fun migrateLegacy(assignments: Map<ViopContractCategory, Collection<String>>) {
        if (isCategoryMigrationComplete()) return
        val editor = prefs.edit()
        WATCHLIST_CATEGORIES.forEach { category ->
            val merged = (symbols(category) + assignments[category].orEmpty())
                .map(::normalizeSymbol)
                .filter(::isValidSymbol)
                .distinct()
                .take(MAX_ITEMS)
                .toSet()
            editor.putStringSet(categoryKey(category), merged)
        }
        editor.putBoolean(KEY_CATEGORY_MIGRATED, true).apply()
    }

    enum class AddResult { ADDED, ALREADY_EXISTS, LIMIT_REACHED, INVALID }

    companion object {
        const val MAX_ITEMS = 20
        private const val PREFS = "viop_watchlist"
        private const val LEGACY_KEY = "symbols"
        private const val KEY_CATEGORY_MIGRATED = "category_watchlists_migrated_v1"

        val WATCHLIST_CATEGORIES = listOf(
            ViopContractCategory.BIST30,
            ViopContractCategory.FX,
            ViopContractCategory.COMMODITY,
            ViopContractCategory.OTHER
        )

        fun categoryKey(category: ViopContractCategory): String = when (category) {
            ViopContractCategory.BIST30 -> "symbols_bist30"
            ViopContractCategory.FX -> "symbols_currency"
            ViopContractCategory.COMMODITY -> "symbols_commodity"
            ViopContractCategory.OTHER -> "symbols_other"
            ViopContractCategory.ALL -> "symbols_invalid_all"
        }

        fun normalizeSymbol(raw: String): String = raw
            .trim()
            .uppercase(Locale.ROOT)
            .replace('İ', 'I')

        data class AddMutation(val result: AddResult, val symbols: List<String>)

        fun addTo(currentRaw: Collection<String>, rawSymbol: String): AddMutation {
            val current = currentRaw.map(::normalizeSymbol).filter(::isValidSymbol).toMutableSet()
            val symbol = normalizeSymbol(rawSymbol)
            if (!isValidSymbol(symbol)) return AddMutation(AddResult.INVALID, current.sorted())
            if (symbol in current) return AddMutation(AddResult.ALREADY_EXISTS, current.sorted())
            if (current.size >= MAX_ITEMS) return AddMutation(AddResult.LIMIT_REACHED, current.sorted())
            current += symbol
            return AddMutation(AddResult.ADDED, current.sorted())
        }

        fun removeFrom(currentRaw: Collection<String>, rawSymbol: String): List<String> {
            val symbol = normalizeSymbol(rawSymbol)
            return currentRaw.map(::normalizeSymbol)
                .filter(::isValidSymbol)
                .filterNot { it == symbol }
                .distinct()
                .sorted()
        }

        private fun isValidSymbol(symbol: String): Boolean =
            symbol.matches(Regex("[A-Z0-9_.-]{3,48}"))
    }
}
