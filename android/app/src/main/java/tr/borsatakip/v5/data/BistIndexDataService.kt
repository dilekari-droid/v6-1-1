package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import tr.borsatakip.v5.model.Stock

/**
 * BIST benchmark/endeks kartları için tek veri yolu.
 * Seans açıkken doğrulanmış canlı quote + 5 DK OHLCV, seans dışında ise yalnız kapanmış
 * 5 DK OHLCV kullanılır. Tahmini/endeks türetimi yapılmaz.
 */
class BistIndexDataService(private val context: Context) {
    suspend fun load(symbol: String): Stock? = loadMany(listOf(symbol))[symbol.trim().uppercase()]

    suspend fun loadMany(symbols: List<String>): Map<String, Stock> {
        val requested = symbols.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        if (requested.isEmpty()) return emptyMap()
        val preflight = BackendPreflightClient(context).checkBist()
        if (!preflight.ok) return emptyMap()
        val sessionClose = preflight.bistAvailabilityMode == BackendPreflightClient.BistAvailabilityMode.SESSION_CLOSE
        val provider = IntervalMarketDataProvider(
            ProviderRouter(context),
            intervalMinutes = 5,
            sessionCloseMode = sessionClose
        )
        val out = linkedMapOf<String, Stock>()
        for (symbol in requested) {
            try {
                provider.fetchOne(symbol)?.let { out[symbol] = it }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Tek endeks hatası diğer benchmark kartlarını engellemez. Veri uydurulmaz.
            }
        }
        return out
    }
}
