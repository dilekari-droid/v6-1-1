package tr.borsatakip.v5.data.favorites

import android.content.Context
import tr.borsatakip.v5.data.ScanTimeframe

class FavoriteRepository private constructor(private val context: Context) {
    private val dao = FavoritesDatabase.get(context).favoriteDao()

    suspend fun getAll(): List<FavoriteStock> = dao.getAll()

    suspend fun getByMarket(market: String): List<FavoriteStock> =
        dao.getByMarket(normalizeMarket(market))

    suspend fun symbols(market: String = "BIST"): Set<String> =
        getByMarket(market).map { it.symbol }.toSet()

    suspend fun isFavorite(rawSymbol: String, market: String = "BIST"): Boolean =
        dao.contains(normalizeSymbol(rawSymbol), normalizeMarket(market))

    suspend fun add(rawSymbol: String, displayName: String? = null, market: String = "BIST", analysisIntervalMinutes: Int? = null) {
        val symbol = normalizeSymbol(rawSymbol)
        val normalizedMarket = normalizeMarket(market)
        require(symbol.matches(Regex("[A-Z0-9]{3,20}"))) { "Geçersiz sembol" }
        dao.upsert(
            FavoriteStock(
                symbol = symbol,
                displayName = displayName?.trim()?.takeIf { it.isNotBlank() },
                market = normalizedMarket,
                analysisIntervalMinutes = analysisIntervalMinutes?.let { ScanTimeframe.normalizeStoredMinutes(it) }
            )
        )
    }

    suspend fun remove(rawSymbol: String, market: String = "BIST") {
        dao.delete(normalizeSymbol(rawSymbol), normalizeMarket(market))
    }

    suspend fun toggle(rawSymbol: String, displayName: String? = null, market: String = "BIST", analysisIntervalMinutes: Int? = null): Boolean {
        val symbol = normalizeSymbol(rawSymbol)
        val normalizedMarket = normalizeMarket(market)
        return if (dao.contains(symbol, normalizedMarket)) {
            dao.delete(symbol, normalizedMarket)
            false
        } else {
            add(symbol, displayName, normalizedMarket, analysisIntervalMinutes)
            true
        }
    }

    suspend fun migrateLegacyIfNeeded() {
        val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
        if (prefs.getBoolean("room_migrated", false)) return
        val legacy = prefs.getStringSet("bist", emptySet()).orEmpty()
        var failures = 0
        legacy.forEach { raw -> runCatching { add(raw, market = "BIST") }.onFailure { failures++ } }
        if (failures == 0) prefs.edit().remove("bist").putBoolean("room_migrated", true).apply()
        else prefs.edit().putBoolean("room_migrated", false).apply()
    }

    companion object {
        @Volatile private var INSTANCE: FavoriteRepository? = null

        fun get(context: Context): FavoriteRepository = INSTANCE ?: synchronized(this) {
            INSTANCE ?: FavoriteRepository(context.applicationContext).also { INSTANCE = it }
        }

        fun normalizeSymbol(raw: String): String = raw
            .trim()
            .uppercase()
            .removePrefix("BIST:")
            .removeSuffix(".IS")
            .trim()

        fun normalizeMarket(raw: String): String = when (raw.trim().uppercase()) {
            "VIOP", "VİOP" -> "VIOP"
            else -> "BIST"
        }
    }
}
