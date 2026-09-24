package tr.borsatakip.v5.data

import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopContractType

/** Merkezi ve deterministik gerçek VİOP futures kontrat seçimi. */
object ViopContractSelector {
    fun candidates(
        contracts: List<ViopContract>,
        underlying: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        allowWatch: Boolean = false
    ): List<ViopContract> {
        val wanted = underlying?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        return contracts.asSequence()
            .filter { !it.isManual }
            .filter { it.contractType == ViopContractType.FUTURE }
            .filter { wanted == null || it.underlying.trim().uppercase() == wanted }
            .filter { it.validity == SignalValidity.VALID || (allowWatch && it.validity == SignalValidity.WATCH) }
            .filter { it.lastTradingAt?.let { t -> t > nowMs } == true }
            .filter { it.expiryAt?.let { t -> t > nowMs } != false }
            .sortedWith(
                compareBy<ViopContract> { if (it.validity == SignalValidity.VALID) 0 else 1 }
                    .thenBy { it.lastTradingAt ?: it.expiryAt ?: Long.MAX_VALUE }
                    .thenByDescending { it.volume ?: -1.0 }
                    .thenByDescending { it.openInterest ?: -1L }
                    .thenBy { it.symbol }
            )
            .toList()
    }

    fun resolve(
        underlying: String,
        contracts: List<ViopContract>,
        nowMs: Long = System.currentTimeMillis()
    ): ViopContract? = candidates(contracts, underlying, nowMs, allowWatch = false).firstOrNull()
}
