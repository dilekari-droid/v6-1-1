package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.TechnicalSnapshot
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopQuote
import kotlin.math.abs

/**
 * VİOP'a özgü bağlam üretir. Ana V531/V5.4.5 karar motorunun puanını ikinci kez değiştirmez.
 * Spread/OI/vade/volatilite burada açıklama ve risk bağlamıdır; nihai publication tek karar akışındadır.
 */
object ViopSpecificAnalysis {
    data class Result(
        val longScore: Int,
        val shortScore: Int,
        val direction: String,
        val riskAdjustment: Int,
        val reasons: List<String>
    )

    fun evaluate(
        contract: ViopContract,
        quote: ViopQuote,
        technical: TechnicalSnapshot,
        baseLong: Int,
        baseShort: Int,
        expiryRisk: Int,
        spreadPct: Double?
    ): Result {
        var riskAdj = 0
        val reasons = mutableListOf<String>()

        val atrPct = technical.atr14?.takeIf { it.isFinite() && it > 0.0 }?.let { it / quote.price * 100.0 }
        if (atrPct != null) {
            when {
                atrPct > 6.0 -> { riskAdj += 12; reasons += "VİOP volatilitesi yüksek (${format(atrPct)}%)" }
                atrPct > 3.5 -> { riskAdj += 6; reasons += "VİOP volatilitesi orta-yüksek (${format(atrPct)}%)" }
                else -> reasons += "VİOP volatilitesi kontrollü (${format(atrPct)}%)"
            }
        } else reasons += "Volatilite doğrulanamadı"

        if (spreadPct != null) {
            when {
                spreadPct > 1.5 -> { riskAdj += 18; reasons += "Spread geniş (${format(spreadPct)}%)" }
                spreadPct > 0.7 -> { riskAdj += 8; reasons += "Spread izlenmeli (${format(spreadPct)}%)" }
                else -> reasons += "Spread uygun (${format(spreadPct)}%)"
            }
        } else {
            riskAdj += 5
            reasons += "Spread doğrulanamadı"
        }

        val oi = quote.openInterest ?: contract.openInterest
        if (oi != null && oi > 0L) reasons += "Açık pozisyon mevcut ($oi)" else {
            riskAdj += 10
            reasons += "Açık pozisyon verisi yok"
        }
        quote.openInterestChangePct?.takeIf(Double::isFinite)?.let { oiChange ->
            val priceChange = quote.dailyChangePct?.takeIf(Double::isFinite)
            if (priceChange != null) {
                when {
                    priceChange > 0.0 && oiChange > 0.0 -> reasons += "Fiyat+OI artışı LONG teyit bağlamı"
                    priceChange < 0.0 && oiChange > 0.0 -> reasons += "Fiyat düşüşü+OI artışı SHORT teyit bağlamı"
                    oiChange < 0.0 -> reasons += "OI azalıyor; pozisyon çözülmesi riski"
                }
            }
        }

        if (expiryRisk >= 75) {
            riskAdj += 12; reasons += "Yakın vade/rollover riski yüksek"
        } else if (expiryRisk >= 50) {
            riskAdj += 5; reasons += "Vade riski orta"
        }

        // Analiz eğilimi yalnız ana motorun LONG/SHORT bileşenlerinden türetilir; puan eklenmez/çıkarılmaz.
        val direction = OpportunityDirectionalFilterPolicy.fromScores(baseLong, baseShort).name
        return Result(baseLong.coerceIn(0, 100), baseShort.coerceIn(0, 100), direction, riskAdj.coerceIn(0, 30), reasons)
    }

    private fun format(value: Double) = "%.2f".format(java.util.Locale.US, abs(value))
}
