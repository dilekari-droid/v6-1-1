package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.data.RealTimeIntegrityPolicy
import tr.borsatakip.v5.model.MarketDataState
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopContractType
import tr.borsatakip.v5.model.ViopOpportunity
import tr.borsatakip.v5.model.ViopQuote

/**
 * Fail-closed production VİOP gates. Only validated futures contracts may enter analysis/publication.
 * Option contracts are intentionally not published until an option-specific model exists.
 */
object ViopProductionPolicy {
    enum class ContractDisposition { ANALYZE, INSUFFICIENT_DATA, REJECTED, UNSUPPORTED_OPTION }

    data class Accounting(
        val universeTotal: Int,
        val published: Int,
        val longCount: Int,
        val shortCount: Int,
        val watchCount: Int,
        val insufficient: Int,
        val rejected: Int,
        val noData: Int,
        val unsupportedOptions: Int = 0
    ) {
        val accountedTotal: Int get() = published + insufficient + rejected + noData + unsupportedOptions
        val consistent: Boolean get() = accountedTotal == universeTotal && published == longCount + shortCount + watchCount
    }

    fun isOption(contract: ViopContract): Boolean = contract.contractType == ViopContractType.OPTION

    fun contractDisposition(contract: ViopContract): ContractDisposition = when {
        contract.contractType == ViopContractType.OPTION -> ContractDisposition.UNSUPPORTED_OPTION
        contract.contractType != ViopContractType.FUTURE -> ContractDisposition.REJECTED
        contract.validity == SignalValidity.VALID -> ContractDisposition.ANALYZE
        contract.validity == SignalValidity.INSUFFICIENT -> ContractDisposition.INSUFFICIENT_DATA
        else -> ContractDisposition.REJECTED
    }

    /** Returns null only when the quote is semantically publishable. */
    fun quoteRejectionCode(contract: ViopContract, quote: ViopQuote, nowMs: Long): String? {
        if (contract.contractType != ViopContractType.FUTURE) return "CONTRACT_TYPE_NOT_FUTURE"
        if (!quote.symbol.equals(contract.symbol, ignoreCase = true)) return "SYMBOL_MISMATCH"
        if (!quote.price.isFinite() || quote.price <= 0.0) return "INVALID_PRICE"
        if (quote.exchangeTimestamp <= 0L) return "INVALID_TIMESTAMP"
        val rawAge = nowMs - quote.exchangeTimestamp
        if (rawAge < -RealTimeIntegrityPolicy.MAX_FUTURE_CLOCK_SKEW_MS) return "FUTURE_TIMESTAMP"
        if (rawAge > ViopScanner.MAX_DATA_AGE_MS) return "STALE_DATA"
        val delay = quote.delaySeconds ?: return "DELAY_UNVERIFIED"
        if (delay !in 0..RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS) return "DELAY_UNVERIFIED"
        val metadata = quote.marketDataMetadata ?: return "METADATA_MISSING"
        if (metadata.state != MarketDataState.LIVE || metadata.isFallback || metadata.isOffline || !metadata.isLive) {
            return "DATA_NOT_LIVE"
        }
        return null
    }

    fun accounting(
        universeTotal: Int,
        opportunities: List<ViopOpportunity>,
        insufficient: Int,
        rejected: Int,
        noData: Int,
        unsupportedOptions: Int = 0
    ): Accounting {
        val longCount = opportunities.count { ViopSignalPolicy.publishedDirection(it) == ViopSignalPolicy.PublishedDirection.LONG }
        val shortCount = opportunities.count { ViopSignalPolicy.publishedDirection(it) == ViopSignalPolicy.PublishedDirection.SHORT }
        val watchCount = opportunities.size - longCount - shortCount
        return Accounting(
            universeTotal = universeTotal,
            published = opportunities.size,
            longCount = longCount,
            shortCount = shortCount,
            watchCount = watchCount,
            insufficient = insufficient,
            rejected = rejected,
            noData = noData,
            unsupportedOptions = unsupportedOptions
        )
    }
}
