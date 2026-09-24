package tr.borsatakip.v5.data

import android.content.Context
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopContract

class ViopRepository(context: Context) {
    private val settings = SettingsStore(context)
    private val local = ViopLocalStore(context)
    private val backend = BackendProvider(context)

    data class RefreshResult(
        val productionItems: List<ViopContract>,
        val manualItems: List<ViopContract>,
        val message: String
    ) {
        val totalProduction: Int get() = productionItems.size
        val validCount: Int get() = productionItems.count { it.validity == SignalValidity.VALID }
        val watchCount: Int get() = productionItems.count { it.validity == SignalValidity.WATCH }
        val insufficientCount: Int get() = productionItems.count { it.validity == SignalValidity.INSUFFICIENT }
        val rejectedCount: Int get() = productionItems.count { it.validity == SignalValidity.REJECTED }
    }

    fun loadLocal(): List<ViopContract> = local.load()
    fun loadManual(): List<ViopContract> = local.load().filter { it.isManual }

    fun addManual(contract: ViopContract): Result<Unit> = local.add(contract.copy(isManual = true))

    fun removeManual(symbol: String) = local.remove(symbol)

    /**
     * Production taraması ile manuel/demo kayıtları birbirine karıştırmaz.
     * TradingView VİOP veri kaynağı değildir ve hiçbir katman sahte fiyat/sinyal üretmez.
     */
    suspend fun refreshDetailed(): RefreshResult {
        val manualItems = loadManual()
        if (!ProviderReadinessService.isValidHttps(settings.baseUrl)) {
            return RefreshResult(
                productionItems = emptyList(),
                manualItems = manualItems,
                message = "BLOCKED • Production VİOP backend'i yapılandırılmamış. Gerçek tarama başlatılmadı."
            )
        }

        val remote = backend.loadViop()
        if (remote.isFailure) {
            val message = remote.exceptionOrNull()?.message ?: "Backend VİOP verisi alınamadı."
            return RefreshResult(
                productionItems = emptyList(),
                manualItems = manualItems,
                message = "BLOCKED • $message • TradingView fallback kullanılmadı."
            )
        }

        val productionItems = remote.getOrDefault(emptyList())
            .filterNot { it.isManual }
            .distinctBy { it.symbol.uppercase() }
            .sortedWith(compareByDescending<ViopContract> { it.validity == SignalValidity.VALID }.thenBy { it.symbol })

        if (productionItems.isEmpty()) {
            return RefreshResult(
                productionItems = emptyList(),
                manualItems = manualItems,
                message = "BLOCKED • Production backend aktif VİOP sözleşmesi döndürmedi."
            )
        }

        return RefreshResult(
            productionItems = productionItems,
            manualItems = manualItems,
            message = "Production VİOP evreni alındı • ${productionItems.size} gerçek sözleşme"
        )
    }

    /** Geriye dönük çağrılar için. */
    suspend fun refresh(): Pair<List<ViopContract>, String> {
        val r = refreshDetailed()
        val visible = if (r.productionItems.isNotEmpty()) r.productionItems else r.manualItems
        return visible to r.message
    }
}
