package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Opportunity

data class Opportunity2Presentation(
    val klass: String,
    val setup: String,
    val riskReward: Double?,
    val timing: String,
    val reasons: List<String>,
    val risks: List<String>
) {
    companion object {
        fun from(x: Opportunity): Opportunity2Presentation {
            fun value(prefix: String) = x.scoreBreakdown.firstOrNull { it.startsWith(prefix) }?.substringAfter(prefix)?.trim()
            // İş mantığı typed alanlardan okunur; scoreBreakdown yalnız geriye dönük açıklama fallback'idir.
            val klass = x.qualityClass.takeIf { it.isNotBlank() } ?: value("CLASS=") ?: "İZLE"
            val setup = x.setupType.takeIf { it.isNotBlank() } ?: value("SETUP=") ?: "TEKNİK YAPI"
            val rr = x.riskPlan?.rr1 ?: value("RR1=")?.toDoubleOrNull() ?: value("RR=")?.toDoubleOrNull()
            val timing = x.timingStatus.takeIf { it.isNotBlank() } ?: value("TIMING=") ?: "DEĞERLENDİRİLMEDİ"
            val reasons = x.scoreBreakdown.filter { it.startsWith("WHY=") }.map { it.substringAfter("WHY=") }
            val risks = x.scoreBreakdown.filter { it.startsWith("RISK_NOTE=") }.map { it.substringAfter("RISK_NOTE=") }
            return Opportunity2Presentation(klass, setup, rr, timing, reasons, risks)
        }
    }
}
