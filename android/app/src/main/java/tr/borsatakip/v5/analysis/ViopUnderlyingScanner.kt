package tr.borsatakip.v5.analysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tr.borsatakip.v5.data.MarketDataProvider
import tr.borsatakip.v5.data.ViopBuiltinContractCatalog
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.ViopContract

/**
 * Production VİOP quote/history bulunmadığında kullanılan DAYANAK ÖN TARAMA modu.
 * Bu sınıf VİOP fiyatı, hacmi veya açık pozisyon üretmez. Yalnız ilgili BIST dayanağının
 * mevcut teknik motor çıktısını en yakın yerel kontrat etiketiyle birlikte sunar.
 */
class ViopUnderlyingScanner(private val provider: MarketDataProvider) {
    data class Candidate(
        val contract: ViopContract,
        val underlying: Opportunity
    )

    data class Result(
        val items: List<Candidate>,
        val attempted: Int,
        val resolved: Int,
        val failed: Int
    )

    suspend fun scan(): Result = coroutineScope {
        val nearestContracts = ViopBuiltinContractCatalog.current()
            .filter { it.underlying.matches(Regex("[A-Z0-9_]{3,12}")) }
            .filterNot { it.underlying == "XU030" }
            .groupBy { it.underlying }
            .mapNotNull { (_, items) -> items.minByOrNull { it.expiryAt ?: Long.MAX_VALUE } }
            .sortedBy { it.underlying }

        val semaphore = Semaphore(CONCURRENCY)
        val rows = nearestContracts.map { contract ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val stock = runCatching { provider.fetchOne(contract.underlying) }.getOrNull()
                    val opportunity = stock?.let { OpportunityEngine.score(it) }
                    if (opportunity == null) null else Candidate(contract, opportunity)
                }
            }
        }.awaitAll()

        val items = rows.filterNotNull().sortedWith(
            compareByDescending<Candidate> { it.underlying.rankingScore }
                .thenByDescending { it.underlying.finalSignalScore }
                .thenBy { it.contract.underlying }
        )
        Result(items, nearestContracts.size, items.size, nearestContracts.size - items.size)
    }

    companion object {
        const val CONCURRENCY = 4
    }
}
