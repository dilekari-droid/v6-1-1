package tr.borsatakip.v5.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock

/** Display-only last-known-good cache. Cached data is always OFFLINE/STALE and never LIVE. */
class MarketDataCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("market_data_cache_v543", Context.MODE_PRIVATE)

    fun save(stock: Stock) {
        val price = stock.quotePrice ?: stock.candles.lastOrNull()?.close ?: return
        if (!price.isFinite() || price <= 0.0 || stock.exchangeTimestamp <= 0L) return
        val candles = JSONArray()
        stock.candles.takeLast(MAX_CANDLES).forEach { c ->
            candles.put(JSONObject().put("t", c.timestamp).put("o", c.open).put("h", c.high).put("l", c.low).put("c", c.close).put("v", c.volume))
        }
        val x = JSONObject()
            .put("symbol", stock.symbol)
            .put("name", stock.companyName)
            .put("source", stock.source)
            .put("timestamp", stock.exchangeTimestamp)
            .put("receivedAt", stock.receivedAt)
            .put("quotePrice", price)
            .put("previousClose", stock.previousClose)
            .put("currency", stock.currency)
            .put("market", stock.market)
            .put("interval", stock.interval)
            .put("candles", candles)
        prefs.edit().putString(key(stock.symbol), x.toString()).apply()
    }

    fun load(symbol: String): Stock? {
        val raw = prefs.getString(key(symbol), null) ?: return null
        return runCatching {
            val x = JSONObject(raw)
            val normalized = x.getString("symbol").trim().uppercase()
            if (normalized != symbol.trim().uppercase()) return null
            val arr = x.optJSONArray("candles") ?: JSONArray()
            val candles = buildList {
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    add(Candle(c.getLong("t"), c.getDouble("o"), c.getDouble("h"), c.getDouble("l"), c.getDouble("c"), c.getDouble("v")))
                }
            }
            val ts = x.getLong("timestamp")
            val receivedAt = System.currentTimeMillis()
            Stock(
                symbol = normalized,
                companyName = x.optString("name").takeIf { it.isNotBlank() },
                candles = candles,
                source = x.optString("source").ifBlank { "CACHE" } + " • CACHE",
                dataTimestamp = ts,
                isRealtime = false,
                delaySeconds = null,
                currentSessionIncluded = false,
                receivedAt = receivedAt,
                quotePrice = x.optDouble("quotePrice", Double.NaN).takeIf { it.isFinite() && it > 0.0 },
                currency = x.optString("currency").takeIf { it.isNotBlank() },
                market = x.optString("market").ifBlank { "BIST" },
                interval = x.optString("interval").ifBlank { "1d" },
                previousClose = x.optDouble("previousClose", Double.NaN).takeIf { it.isFinite() && it > 0.0 },
                marketDataMetadata = MarketDataQuality.metadata(
                    providerId = "cache",
                    source = x.optString("source").ifBlank { "CACHE" },
                    marketTimestamp = ts,
                    receivedAt = receivedAt,
                    isLive = false,
                    delaySeconds = null,
                    lastSuccessfulUpdateAt = x.optLong("receivedAt", ts),
                    fallback = true,
                    offline = true,
                    reason = "Son geçerli veri cache'den gösteriliyor; yeni sinyal üretiminde LIVE kabul edilmez."
                )
            )
        }.getOrNull()
    }

    private fun key(symbol: String) = "quote_${symbol.trim().uppercase()}"

    companion object { private const val MAX_CANDLES = 260 }
}
