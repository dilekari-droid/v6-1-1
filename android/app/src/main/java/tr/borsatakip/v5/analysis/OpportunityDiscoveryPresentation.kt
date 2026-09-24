package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Opportunity
import kotlin.math.roundToInt

data class DiscoveryFactor(
    val label: String,
    val score: Int?,
    val detail: String
)

data class OpportunityDiscoveryPresentation(
    val opportunityScore: Int,
    val coveragePct: Int,
    val classification: String,
    val reason: String,
    val factors: List<DiscoveryFactor>,
    val catalystAvailable: Boolean,
    val highVolume: Boolean,
    val lowRisk: Boolean
) {
    companion object {
        fun from(x: Opportunity): OpportunityDiscoveryPresentation {
            val t = x.technical
            val factors = mutableListOf<DiscoveryFactor>()

            // The current production market-data contract does not expose these fundamentals.
            // They remain explicitly unavailable; the UI must never fabricate them.
            factors += DiscoveryFactor("Değerleme", null, "Temel değerleme verisi sağlanmıyor")
            factors += DiscoveryFactor("Finansal Kalite", null, "Bilanço kalite verisi sağlanmıyor")
            factors += DiscoveryFactor("Kâr İvmesi", null, "Dönemsel kâr serisi sağlanmıyor")
            factors += DiscoveryFactor("Kâr/Ciro Sürprizi", null, "Beklenti-gerçekleşme verisi sağlanmıyor")

            val emaBull = t.ema20 != null && t.ema50 != null && t.ema200 != null &&
                x.price > t.ema20 && t.ema20 > t.ema50 && t.ema50 > t.ema200
            val emaBear = t.ema20 != null && t.ema50 != null && t.ema200 != null &&
                x.price < t.ema20 && t.ema20 < t.ema50 && t.ema50 < t.ema200
            val dailyChange = x.dailyChangePct
            val priceStrength = when {
                emaBull && dailyChange != null && dailyChange >= 0 -> 88
                emaBull -> 78
                emaBear && dailyChange != null && dailyChange <= 0 -> 22
                emaBear -> 32
                dailyChange != null && dailyChange >= 2.0 -> 72
                dailyChange != null && dailyChange <= -2.0 -> 28
                else -> 52
            }
            factors += DiscoveryFactor("Fiyat Gücü", priceStrength, "EMA dizilimi ve fiyat davranışı")

            val rsi = t.rsi14
            val macd = t.macd
            val macdSignal = t.macdSignal
            val momentum = when {
                rsi == null || macd == null || macdSignal == null -> null
                rsi in 52.0..68.0 && macd > macdSignal -> 88
                rsi in 45.0..70.0 && macd > macdSignal -> 72
                rsi in 32.0..48.0 && macd < macdSignal -> 30
                else -> 50
            }
            factors += DiscoveryFactor("Momentum", momentum, "RSI ve MACD birlikte değerlendirildi")

            val volumeRatio = t.volumeRatio?.takeIf { it.isFinite() && it >= 0.0 }
            val volumeScore = volumeRatio?.let { (it * 45.0).coerceIn(0.0, 100.0).roundToInt() }
            factors += DiscoveryFactor("Hacim", volumeScore, volumeRatio?.let { "20 periyot ortalamasının %.2fx'i".format(it) } ?: "Hacim oranı yok")

            factors += DiscoveryFactor("Para Akışı", null, "Gerçek para akışı göstergesi bu veri sözleşmesinde yok")

            val catalystAvailable = x.kapLabel.isNotBlank() &&
                !x.kapLabel.equals("Veri yok", true) &&
                !x.kapLabel.equals("Doğrulanmadı", true) &&
                x.kapLabel.startsWith("VERIFIED:", true)
            factors += DiscoveryFactor(
                "KAP/Katalizör",
                null,
                if (catalystAvailable) x.kapLabel else "Doğrulanmış katalizör verisi yok"
            )

            val riskQuality = (100 - x.riskScore).coerceIn(0, 100)
            factors += DiscoveryFactor("Risk", riskQuality, "Risk skoru ${x.riskScore}/100")
            factors += DiscoveryFactor("Veri Kalitesi", x.dataConfidenceScore.coerceIn(0, 100), "${x.dataConfidenceLabel} veri güveni")
            factors += DiscoveryFactor("Piyasa Rejimi", null, "Benchmark/piyasa genişliği verisi sağlanmıyor")

            val available = factors.filter { it.score != null }
            val coveragePct = ((available.size.toDouble() / factors.size.toDouble()) * 100.0).roundToInt()
            val isShort = x.direction.equals("SHORT", true)
            val directionalScores = available.mapNotNull { factor ->
                val score = factor.score ?: return@mapNotNull null
                when {
                    isShort && factor.label in setOf("Fiyat Gücü", "Momentum") -> 100 - score
                    else -> score
                }
            }
            val availableMean = if (directionalScores.isEmpty()) 0.0 else directionalScores.average()
            val blended = ((availableMean * 0.55) + (x.finalSignalScore.coerceIn(0, 100) * 0.45)).roundToInt().coerceIn(0, 100)

            // Technical-only mode is explicit: unavailable fundamental/regime/catalyst data never receive fake points.
            // The classification can still work from the seven available technical/data-quality factors.
            val technicalAvailable = available.size >= 5
            val classification = when (x.decisionState) {
                tr.borsatakip.v5.model.DecisionState.INSUFFICIENT_DATA -> "YETERSİZ VERİ"
                tr.borsatakip.v5.model.DecisionState.REJECTED -> "REDDEDİLDİ"
                else -> {
                    val verified = x.decisionState == tr.borsatakip.v5.model.DecisionState.VERIFIED_OPPORTUNITY
                    when {
                        technicalAvailable && blended >= 82 && x.finalSignalScore >= 80 && x.direction.equals("LONG", true) && x.riskScore <= 40 -> if (verified) "GÜÇLÜ AL" else "GÜÇLÜ AL ADAYI"
                        technicalAvailable && blended >= 70 && x.finalSignalScore >= 70 && x.direction.equals("LONG", true) && x.riskScore <= 55 -> if (verified) "AL" else "AL ADAYI"
                        technicalAvailable && blended >= 82 && x.finalSignalScore >= 80 && x.direction.equals("SHORT", true) && x.riskScore <= 40 -> if (verified) "GÜÇLÜ SAT" else "GÜÇLÜ SAT ADAYI"
                        technicalAvailable && blended >= 70 && x.finalSignalScore >= 70 && x.direction.equals("SHORT", true) && x.riskScore <= 55 -> if (verified) "SAT" else "SAT ADAYI"
                        else -> "İZLE"
                    }
                }
            }
            val reason = when (x.decisionState) {
                tr.borsatakip.v5.model.DecisionState.INSUFFICIENT_DATA,
                tr.borsatakip.v5.model.DecisionState.REJECTED -> x.signalValidityReason
                else -> buildList {
                    if (x.direction.equals("SHORT", true)) {
                        if (priceStrength <= 35) add("aşağı yönlü fiyat yapısı")
                        if ((momentum ?: 100) <= 35) add("negatif momentum")
                    } else {
                        if (priceStrength >= 70) add("güçlü fiyat yapısı")
                        if ((momentum ?: 0) >= 70) add("pozitif momentum")
                    }
                    if ((volumeRatio ?: 0.0) >= 1.5) add("hacim teyidi")
                    if (x.riskScore <= 35) add("düşük teknik risk")
                    if (catalystAvailable) add("doğrulanmış KAP/katalizör")
                    if (!catalystAvailable) add("katalizör verisi doğrulanmadı; teknik mod")
                }.joinToString(" + ").ifBlank { "mevcut veride belirgin çok-faktörlü teyit oluşmadı" }
            }

            return OpportunityDiscoveryPresentation(
                opportunityScore = blended,
                coveragePct = coveragePct,
                classification = classification,
                reason = reason,
                factors = factors,
                catalystAvailable = catalystAvailable,
                highVolume = (volumeRatio ?: 0.0) >= 1.5,
                lowRisk = x.riskScore <= 35
            )
        }
    }
}
