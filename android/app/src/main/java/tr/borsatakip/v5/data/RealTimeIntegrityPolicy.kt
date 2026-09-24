package tr.borsatakip.v5.data

import android.os.SystemClock
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock

/** Fail-closed BIST veri bütünlük kapısı. */
object RealTimeIntegrityPolicy {
    const val MAX_DATA_AGE_MS = 60_000L
    const val MAX_DECLARED_DELAY_SECONDS = 5
    const val MAX_FUTURE_CLOCK_SKEW_MS = 15_000L
    const val MIN_HISTORY_BARS = 220 // compatibility alias; canonical requirement comes from CandleReadinessPolicy
    private const val MAX_DAILY_HISTORY_GAP_MS = 7L * 24 * 60 * 60 * 1000

    data class Verdict(val accepted:Boolean, val reason:String, val measuredAgeMs:Long? = null)

    fun validate(stock:Stock, nowElapsed:Long = SystemClock.elapsedRealtime(), nowWall:Long = System.currentTimeMillis()):Verdict {
        if (!stock.isRealtime) return Verdict(false, "Sağlayıcı veriyi gerçek zamanlı olarak doğrulamadı.")
        if (!stock.currentSessionIncluded) return Verdict(false, "Güncel işlem seansı OHLCV verisine dahil değil.")
        val delay = stock.delaySeconds ?: return Verdict(false, "Sağlayıcı gecikme bilgisini bildirmedi.")
        if (delay !in 0..MAX_DECLARED_DELAY_SECONDS) return Verdict(false, "Sağlayıcı gecikmesi gerçek zaman eşiğini aşıyor: $delay sn.")
        if (stock.exchangeTimestamp <= 0L) return Verdict(false, "Piyasa veri zamanı yok.")
        if (stock.receivedAt <= 0L || stock.receivedElapsedRealtime <= 0L) return Verdict(false, "Veri alma zamanı doğrulanamadı.")
        if (stock.market.uppercase() != "BIST") return Verdict(false, "Piyasa kimliği BIST ile eşleşmiyor.")
        if (!stock.historySymbol.isNullOrBlank() && stock.historySymbol.uppercase() != stock.symbol.uppercase()) return Verdict(false, "Quote ve history sembolü eşleşmiyor.")

        val ageAtReceipt = stock.receivedAt - stock.exchangeTimestamp
        if (ageAtReceipt < -MAX_FUTURE_CLOCK_SKEW_MS) return Verdict(false, "Piyasa veri zamanı alma zamanından ileride; saat bütünlüğü doğrulanamadı.")
        val elapsedSinceReceipt = (nowElapsed - stock.receivedElapsedRealtime).coerceAtLeast(0L)
        val measuredAge = ageAtReceipt.coerceAtLeast(0L) + elapsedSinceReceipt
        if (measuredAge > MAX_DATA_AGE_MS) return Verdict(false, "Piyasa verisi güncel değil: ${measuredAge / 1000L} sn ölçülen yaş.", measuredAge)

        val candles = stock.candles
        val bad = validateCandles(candles, nowWall)
        if (bad != null) return Verdict(false, bad, measuredAge)
        val readiness = CandleReadinessPolicy.evaluate(stock, nowWall)
        if (readiness.lastBarClosed != true) {
            return Verdict(false, "Seçilen timeframe son mumu kapanmadı; doğrulanmış AL/SAT sinyali üretilmez.", measuredAge)
        }
        if (!readiness.timeframeReady) return Verdict(false, readiness.reason, measuredAge)
        val last = candles.last()
        if (stock.lastBarTime > 0L && stock.lastBarTime != last.timestamp) return Verdict(false, "History lastBarTime son mumla eşleşmiyor.", measuredAge)
        if (stock.lastBarTime <= 0L) return Verdict(false, "History son mum zamanı belirtilmemiş.", measuredAge)
        if (stock.exchangeTimezone.isNullOrBlank()) return Verdict(false, "History piyasa saat dilimi belirtilmemiş.", measuredAge)
        if (stock.interval.isBlank()) return Verdict(false, "History interval bilgisi belirtilmemiş.", measuredAge)

        // Günlük history'nin güncel quote ile aylar/yıllar öncesinden birleşmesini önle.
        if (stock.interval == "1d") {
            val historyAge = nowWall - last.timestamp
            if (historyAge < -MAX_FUTURE_CLOCK_SKEW_MS) return Verdict(false, "History gelecekte tarihli mum içeriyor.", measuredAge)
            if (historyAge > MAX_DAILY_HISTORY_GAP_MS) return Verdict(false, "History son mumu güncel piyasa döneminden çok eski.", measuredAge)
        }
        return Verdict(true, "ANLIK VERİ DOĞRULANDI", measuredAge)
    }

    fun validateCandles(candles: List<Candle>, nowWall:Long = System.currentTimeMillis()): String? {
        if (candles.isEmpty()) return "OHLCV verisi yok."
        var previousTs = Long.MIN_VALUE
        val seen = HashSet<Long>(candles.size)
        for ((index, c) in candles.withIndex()) {
            if (c.timestamp <= 0L) return "OHLCV[$index] zaman damgası geçersiz."
            if (c.timestamp > nowWall + MAX_FUTURE_CLOCK_SKEW_MS) return "OHLCV[$index] gelecekte tarihli."
            if (!seen.add(c.timestamp)) return "OHLCV tekrar eden zaman damgası içeriyor."
            if (previousTs != Long.MIN_VALUE && c.timestamp <= previousTs) return "OHLCV zaman sırası artan değil."
            previousTs = c.timestamp
            if (listOf(c.open,c.high,c.low,c.close,c.volume).any { !it.isFinite() }) return "OHLCV[$index] sonlu olmayan değer içeriyor."
            if (c.open <= 0.0 || c.high <= 0.0 || c.low <= 0.0 || c.close <= 0.0 || c.volume < 0.0) return "OHLCV[$index] fiyat/hacim kurallarına uymuyor."
            if (c.low > c.high || c.open !in c.low..c.high || c.close !in c.low..c.high) return "OHLCV[$index] open/close low-high aralığıyla tutarsız."
        }
        return null
    }
}


/** Ham BIST history sırasını sessizce onarmadan doğrular. */
object BistHistoryIntegrityPolicy {
    fun requireStrict(candles: List<Candle>, minimum: Int, nowWall: Long = System.currentTimeMillis()): List<Candle> {
        RealTimeIntegrityPolicy.validateCandles(candles, nowWall)?.let {
            throw ProviderException(ProviderFailureCode.BIST_HISTORY_ERROR, it)
        }
        if (candles.size < minimum) {
            throw ProviderException(ProviderFailureCode.EMPTY_DATA, "En az $minimum benzersiz ve geçerli OHLCV mumu gerekli; ${candles.size} alındı.")
        }
        return candles.toList()
    }
}
