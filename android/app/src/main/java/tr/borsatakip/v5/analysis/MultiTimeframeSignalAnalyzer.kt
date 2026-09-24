package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.TechnicalSnapshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Çoklu zaman dilimi teknik sinyal motoru.
 *
 * Not: Buradaki puanlar yatırım getirisi garantisi değildir. Motor, istatistiksel trend ölçüleri
 * (lineer regresyon eğimi ve R²) ile yaygın teknik göstergeleri tek bir açıklanabilir özet altında
 * birleştirir. Ağırlıklar uygulama içi karar destek sezgisidir; akademik olarak evrensel kabul
 * edilmiş bir "al/sat" formülü değildir.
 */
object MultiTimeframeSignalAnalyzer {
    enum class TrendDirection(val label: String, val score: Int) {
        UP("YÜKSELİŞ", 1),
        SIDEWAYS("YATAY", 0),
        DOWN("DÜŞÜŞ", -1),
        INSUFFICIENT("YETERSİZ VERİ", 0)
    }

    data class TrendResult(
        val direction: TrendDirection,
        val slopePct: Double?,
        val rSquared: Double?,
        val sampleCount: Int
    )

    data class TimeframeTrend(val label: String, val result: TrendResult)

    enum class Advice(val label: String) {
        STRONG_BUY("GÜÇLÜ AL"),
        BUY("AL"),
        NEUTRAL("BEKLE / NÖTR"),
        SELL("SAT"),
        STRONG_SELL("GÜÇLÜ SAT")
    }

    data class SignalSummary(
        val advice: Advice,
        val score: Int,
        val confidence: Int,
        val alignmentLabel: String,
        val evidenceSummary: String,
        val riskSummary: String,
        val invalidationSummary: String,
        val explanation: String
    )

    fun trend(candles: List<Candle>): TrendResult {
        val clean = OhlcvResampler.sanitize(candles).takeLast(120)
        if (clean.size < 12) return TrendResult(TrendDirection.INSUFFICIENT, null, null, clean.size)
        val values = clean.map { it.close }
        val n = values.size
        val meanX = (n - 1) / 2.0
        val meanY = values.average()
        if (!meanY.isFinite() || meanY <= 0.0) return TrendResult(TrendDirection.INSUFFICIENT, null, null, n)
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (i in values.indices) {
            val dx = i - meanX
            val dy = values[i] - meanY
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        if (sxx <= 0.0) return TrendResult(TrendDirection.SIDEWAYS, 0.0, 0.0, n)
        val slope = sxy / sxx
        val slopePct = (slope * (n - 1) / meanY) * 100.0
        val r2 = if (syy <= 1e-12) 0.0 else ((sxy * sxy) / (sxx * syy)).coerceIn(0.0, 1.0)
        val direction = when {
            abs(slopePct) < 0.8 || r2 < 0.12 -> TrendDirection.SIDEWAYS
            slopePct > 0.0 -> TrendDirection.UP
            else -> TrendDirection.DOWN
        }
        return TrendResult(direction, slopePct, r2, n)
    }

    fun summarize(
        trends: List<TimeframeTrend>,
        price: Double,
        technical: TechnicalSnapshot
    ): SignalSummary {
        // Uzun zaman dilimlerine daha fazla ağırlık verilir; kısa vadeli gürültünün ana kararı
        // tek başına sürüklemesi engellenir.
        val weights = mapOf("3 DK" to 0.15, "5 DK" to 0.20, "1 SA" to 0.30, "1 GÜN" to 0.35)
        var weightedTrend = 0.0
        var weightUsed = 0.0
        trends.forEach { tf ->
            val w = weights[tf.label] ?: 0.0
            if (tf.result.direction != TrendDirection.INSUFFICIENT && w > 0.0) {
                weightedTrend += tf.result.direction.score * w
                weightUsed += w
            }
        }
        val normalizedTrend = if (weightUsed > 0.0) weightedTrend / weightUsed else 0.0

        var technicalScore = 0.0
        var technicalWeight = 0.0
        fun add(conditionScore: Double?, weight: Double) {
            if (conditionScore != null && conditionScore.isFinite()) {
                technicalScore += conditionScore.coerceIn(-1.0, 1.0) * weight
                technicalWeight += weight
            }
        }
        add(technical.ema20?.let { compare(price, it) }, 0.10)
        add(technical.ema50?.let { compare(price, it) }, 0.12)
        add(technical.ema200?.let { compare(price, it) }, 0.13)
        add(if (technical.macd != null && technical.macdSignal != null) compare(technical.macd, technical.macdSignal) else null, 0.15)
        add(technical.rsi14?.let { rsiContribution(it) }, 0.10)
        val normalizedTechnical = if (technicalWeight > 0.0) technicalScore / technicalWeight else 0.0

        var combined = normalizedTrend * 0.72 + normalizedTechnical * 0.28
        val volume = technical.volumeRatio
        if (volume != null && volume.isFinite()) {
            val confirmation = when {
                volume >= 1.5 -> 1.12
                volume >= 1.1 -> 1.06
                volume < 0.7 -> 0.88
                else -> 1.0
            }
            combined *= confirmation
        }
        val score = (combined.coerceIn(-1.0, 1.0) * 100.0).roundToInt()
        val advice = when {
            score >= 60 -> Advice.STRONG_BUY
            score >= 20 -> Advice.BUY
            score <= -60 -> Advice.STRONG_SELL
            score <= -20 -> Advice.SELL
            else -> Advice.NEUTRAL
        }

        val available = trends.filter { it.result.direction != TrendDirection.INSUFFICIENT }
        val up = available.count { it.result.direction == TrendDirection.UP }
        val down = available.count { it.result.direction == TrendDirection.DOWN }
        val dominant = max(up, down)
        val alignment = when {
            available.size < 2 -> "YETERSİZ VERİ"
            dominant == available.size && dominant >= 3 -> "ÇOK GÜÇLÜ"
            dominant >= 3 -> "GÜÇLÜ"
            dominant == 2 -> "ORTA"
            else -> "KARIŞIK"
        }

        val avgR2 = available.mapNotNull { it.result.rSquared }.takeIf { it.isNotEmpty() }?.average()
        val completeness = dataCompleteness(technical, available.size)
        val agreement = if (available.isEmpty()) 0.0 else dominant.toDouble() / available.size.toDouble()
        val r2Quality = (avgR2 ?: 0.0).coerceIn(0.0, 1.0)
        val volumeQuality = when {
            volume == null || !volume.isFinite() -> 0.45
            volume >= 1.5 -> 1.0
            volume >= 1.1 -> 0.80
            volume >= 0.7 -> 0.60
            else -> 0.35
        }
        val confidence = ((agreement * 0.40 + r2Quality * 0.30 + completeness * 0.20 + volumeQuality * 0.10) * 100.0)
            .roundToInt().coerceIn(0, 100)

        val evidence = mutableListOf<String>()
        available.forEach { tf ->
            val slope = tf.result.slopePct?.let { "%.2f%%".format(it) } ?: "—"
            val r2 = tf.result.rSquared?.let { "%.2f".format(it) } ?: "—"
            val quality = r2Interpretation(tf.result.rSquared)
            evidence += "${tf.label}: ${tf.result.direction.label}. Regresyon değişimi $slope, R² $r2 ($quality)."
        }
        technical.rsi14?.let { rsi ->
            evidence += "RSI(14) %.1f: %s".format(rsi, rsiExplanation(rsi))
        }
        if (technical.macd != null && technical.macdSignal != null) {
            val spread = technical.macd - technical.macdSignal
            evidence += when {
                spread > 0 -> "MACD sinyal çizgisinin %.3f üzerinde; momentum pozitif tarafta.".format(spread)
                spread < 0 -> "MACD sinyal çizgisinin %.3f altında; momentum negatif tarafta.".format(abs(spread))
                else -> "MACD ile sinyal çizgisi eşit; momentum yönü nötr."
            }
        }
        technical.volumeRatio?.let { vr ->
            evidence += when {
                vr >= 1.5 -> "Hacim 20 dönem ortalamasının %.2fx'i: hareket güçlü katılımla destekleniyor.".format(vr)
                vr >= 1.1 -> "Hacim %.2fx: ortalamanın üzerinde, ancak çok güçlü değil.".format(vr)
                vr < 0.7 -> "Hacim %.2fx: zayıf katılım; sinyalin güveni düşürülüyor.".format(vr)
                else -> "Hacim %.2fx: normal aralıkta, ek teyit üretmiyor.".format(vr)
            }
        }
        emaStructure(price, technical)?.let { evidence += it }

        val risks = mutableListOf<String>()
        technical.rsi14?.let {
            if (it >= 70.0) risks += "RSI 70'in üzerinde; kısa vadede yorulma / geri çekilme riski artmış olabilir."
            if (it <= 30.0) risks += "RSI 30'un altında; satış baskısı yüksek, tepki ihtimali olsa da trend riski sürüyor."
        }
        if (avgR2 != null && avgR2 < 0.25) risks += "Regresyon uyumu düşük; fiyat hareketi düzensiz ve trend çizgisi zayıf açıklayıcılığa sahip."
        if (agreement < 0.60 && available.size >= 2) risks += "Zaman dilimleri aynı yönde değil; kısa ve uzun vadeli görüşler çelişiyor."
        technical.atr14?.takeIf { it.isFinite() && it > 0.0 }?.let { atr ->
            val atrPct = atr / price * 100.0
            if (atrPct >= 4.0) risks += "ATR/fiyat oranı %.1f%%; günlük oynaklık yüksek, stop mesafesi genişleyebilir.".format(atrPct)
        }
        if ((technical.volumeRatio ?: 1.0) < 0.7) risks += "Hacim zayıf; fiyat hareketinin kalıcılığı daha düşük olabilir."
        if (risks.isEmpty()) risks += "Belirgin ek risk bayrağı yok; yine de piyasa ve haber riski teknik göstergelerle ölçülemez."

        val invalidation = buildInvalidation(price, technical, advice)

        val evidenceSummary = buildString {
            append("Kanıt kalitesi: $confidence/100. ")
            append("Zaman dilimi uyumu $alignment")
            avgR2?.let { append(", ortalama R² %.2f".format(it)) }
            append(".")
        }
        val riskSummary = risks.joinToString("\n") { "• $it" }
        val invalidationSummary = invalidation

        val explanation = buildString {
            append("Bu sonuç tek bir göstergeye değil, dört zaman dilimindeki trend yönü ile EMA, MACD, RSI ve hacmin birlikte değerlendirilmesine dayanır. ")
            append("Skor yönü; güven puanı ise kanıtın ne kadar tutarlı olduğunu anlatır.\n\n")
            append("NEDEN BU SONUÇ?\n")
            evidence.forEach { append("• ").append(it).append('\n') }
            append("\nNASIL OKUNMALI?\n")
            append("• R², fiyatın doğrusal trend çizgisine ne kadar düzenli uyduğunu gösterir; 1'e yaklaştıkça uyum artar, ancak tek başına al/sat sinyali değildir.\n")
            append("• RSI momentumun hızını, MACD momentum yönünü, EMA'lar trend yapısını, hacim ise harekete katılımı gösterir.\n")
            append("• Kısa vadeli ve günlük yön aynıysa sinyal daha tutarlı; ters yöndeyse karar daha kırılgandır.\n")
            append("\nBu bölüm açıklanabilir teknik karar desteğidir; gelecekteki fiyatı garanti etmez ve kişisel yatırım tavsiyesi değildir.")
        }
        return SignalSummary(advice, score, confidence, alignment, evidenceSummary, riskSummary, invalidationSummary, explanation)
    }

    private fun compare(a: Double, b: Double): Double = when {
        a > b -> 1.0
        a < b -> -1.0
        else -> 0.0
    }

    private fun rsiContribution(rsi: Double): Double = when {
        rsi < 30.0 -> 0.55
        rsi > 70.0 -> -0.55
        rsi >= 55.0 -> 0.35
        rsi <= 45.0 -> -0.35
        else -> 0.0
    }

    private fun rsiExplanation(rsi: Double): String = when {
        rsi >= 70.0 -> "momentum güçlü fakat aşırı alım bölgesine girmiş; yeni giriş için dikkat gerekir."
        rsi >= 55.0 -> "pozitif momentum bölgesinde, henüz klasik aşırı alım eşiğinin altında."
        rsi > 45.0 -> "denge bölgesinde; tek başına yön teyidi vermiyor."
        rsi > 30.0 -> "negatif momentum bölgesinde; satış baskısı daha belirgin."
        else -> "aşırı satım bölgesinde; tepki ihtimali var ancak düşüş trendi devam edebilir."
    }

    private fun r2Interpretation(r2: Double?): String = when {
        r2 == null -> "ölçülemedi"
        r2 >= 0.75 -> "çok düzenli trend"
        r2 >= 0.50 -> "orta-güçlü trend"
        r2 >= 0.25 -> "zayıf-orta trend"
        else -> "düzensiz / düşük trend uyumu"
    }

    private fun dataCompleteness(t: TechnicalSnapshot, timeframes: Int): Double {
        val fields = listOf(t.ema20, t.ema50, t.ema200, t.rsi14, t.macd, t.macdSignal, t.atr14, t.volumeRatio)
        val present = fields.count { it != null && it.isFinite() }
        val technicalCompleteness = present.toDouble() / fields.size.toDouble()
        val timeframeCompleteness = (timeframes.coerceIn(0, 4) / 4.0)
        return technicalCompleteness * 0.65 + timeframeCompleteness * 0.35
    }

    private fun emaStructure(price: Double, t: TechnicalSnapshot): String? {
        val e20 = t.ema20 ?: return null
        val e50 = t.ema50 ?: return null
        val e200 = t.ema200 ?: return null
        return when {
            price > e20 && e20 > e50 && e50 > e200 -> "EMA yapısı yükseliş yönünde sıralı (Fiyat > EMA20 > EMA50 > EMA200); orta-uzun vadeli trend yapısı pozitif."
            price < e20 && e20 < e50 && e50 < e200 -> "EMA yapısı düşüş yönünde sıralı (Fiyat < EMA20 < EMA50 < EMA200); orta-uzun vadeli trend yapısı negatif."
            else -> "EMA'lar tam sıralı değil; trend yapısı geçiş / kararsızlık gösteriyor."
        }
    }

    private fun buildInvalidation(price: Double, t: TechnicalSnapshot, advice: Advice): String {
        val atr = t.atr14?.takeIf { it.isFinite() && it > 0.0 }
        val support = t.support?.takeIf { it.isFinite() && it > 0.0 }
        val resistance = t.resistance?.takeIf { it.isFinite() && it > 0.0 }
        return when (advice) {
            Advice.STRONG_BUY, Advice.BUY -> {
                val level = listOfNotNull(support, t.ema20).filter { it < price }.maxOrNull()
                when {
                    level != null && atr != null -> "Pozitif senaryonun zayıfladığı ilk bölge yaklaşık %.2f. Bu seviyenin altında ATR kadar kalıcılaşma, yükseliş tezini belirgin biçimde bozar.".format(level)
                    level != null -> "Pozitif senaryonun zayıfladığı ilk teknik bölge yaklaşık %.2f. Bu seviyenin altında kalıcılık yükseliş tezini bozar.".format(level)
                    else -> "Net geçersizleşme seviyesi üretmek için destek/EMA verisi yetersiz."
                }
            }
            Advice.STRONG_SELL, Advice.SELL -> {
                val level = listOfNotNull(resistance, t.ema20).filter { it > price }.minOrNull()
                when {
                    level != null && atr != null -> "Negatif senaryonun zayıfladığı ilk bölge yaklaşık %.2f. Bu seviyenin üzerinde ATR kadar kalıcılaşma, düşüş tezini belirgin biçimde bozar.".format(level)
                    level != null -> "Negatif senaryonun zayıfladığı ilk teknik bölge yaklaşık %.2f. Bu seviyenin üzerinde kalıcılık düşüş tezini bozar.".format(level)
                    else -> "Net geçersizleşme seviyesi üretmek için direnç/EMA verisi yetersiz."
                }
            }
            Advice.NEUTRAL -> "Zaman dilimleri ve göstergeler net bir yön üretmediği için tek bir geçersizleşme seviyesi vermek uygun değil. Yeni teyit beklenmeli."
        }
    }
}
