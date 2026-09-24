package tr.borsatakip.v5.data

import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock

/** Bir tarama turunda veri alınamayan ve hata veren sembolleri birbirinden ayırır. */
data class ProviderScanDiagnostics(
    val noDataSymbols: List<String> = emptyList(),
    val errorSymbols: List<String> = emptyList(),
    val messages: Map<String, String> = emptyMap()
) {
    val noDataCount: Int get() = noDataSymbols.distinct().size
    val errorCount: Int get() = errorSymbols.distinct().size
}

interface ScanDiagnosticsSource {
    fun scanDiagnostics(): ProviderScanDiagnostics
}

/** Optional per-symbol progress metadata used by foreground scan UI/notification. */
interface ScanProgressMetadataSource {
    fun currentScanSymbol(): String?
}

/** Allows the outer timeframe provider to ask the backend batch call for the same OHLCV period. */
interface ScanBatchTimeframeConfigurable {
    fun configureScanBatchTimeframe(intervalMinutes: Int, minimumBars: Int)
}

/** Veri sağlayıcılarından bağımsız ortak sözleşme. */
interface MarketDataProvider {
    val id: String
    val displayName: String

    /** MTF cache kimliği gerçek veri kaynağını/origin'i ayırmalıdır. */
    fun mtfCacheSourceKey(): String = id
    suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock>
    suspend fun fetchOne(symbol: String): Stock?
    suspend fun listSymbols(): List<String> = emptyList()

    /**
     * Forward performans doğrulaması ve timeframe taraması için zaman pencereli gerçek tarihsel veri.
     * Sağlayıcı bu yeteneği desteklemiyorsa boş liste döndürür; güncel quote tarihsel fiyat gibi kullanılmaz.
     */
    suspend fun fetchHistory(symbol: String, fromTime: Long, toTime: Long, intervalMinutes: Int = 1): List<Candle> = emptyList()

    /** Grafik/tarama için gerçek günlük geçmiş. 1 GÜN seçimi bu yolu kullanır; 240 DK kullanılmaz. */
    suspend fun fetchDailyHistory(symbol: String, maximumRange: Boolean = false): List<Candle> =
        fetchOne(symbol)?.candles ?: emptyList()
}
