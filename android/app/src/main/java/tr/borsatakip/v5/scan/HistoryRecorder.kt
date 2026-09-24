package tr.borsatakip.v5.scan

import tr.borsatakip.v5.model.Opportunity

/**
 * Tarama sonucunun kalıcı sinyal geçmişine aktarılmasını UI katmanından ayırır.
 * V5.1.48 ile, geçmiş forward performansından türetilen kontrollü strateji ağırlıklarının
 * tarama sonucuna uygulanması da aynı merkezi köprüden geçer. Varsayılan davranış no-op'tur.
 */
interface HistoryRecorder {
    suspend fun applyStrategyOptimization(items: List<Opportunity>): List<Opportunity> = items
    suspend fun record(state: ScanState): HistoryRecordResult
}

data class HistoryRecordResult(
    val inserted: Int = 0,
    val errorMessage: String? = null
)
