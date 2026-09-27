package tr.borsatakip.v5.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DynamicResultFilter { ALL, LONG, SHORT, WATCH, INVALID }

data class DynamicMarketScanResultState(
    val result: DynamicMultiScanResult? = null,
    val updatedAt: Long = 0L
)

/** One canonical result source for TÜMÜ/LONG/SHORT/WATCH/INVALID. */
object DynamicMarketScanResultRepository {
    private val mutable = MutableStateFlow(DynamicMarketScanResultState())
    val state: StateFlow<DynamicMarketScanResultState> = mutable.asStateFlow()

    fun publish(result: DynamicMultiScanResult) {
        mutable.value = DynamicMarketScanResultState(result, System.currentTimeMillis())
    }

    fun snapshot(): DynamicMarketScanResultState = mutable.value

    fun clear() {
        mutable.value = DynamicMarketScanResultState()
    }

    fun filteredItems(filter: DynamicResultFilter): List<DynamicMarketScannerClient.Item> {
        val items = mutable.value.result?.items.orEmpty()
        if (filter == DynamicResultFilter.INVALID) return emptyList()
        if (filter == DynamicResultFilter.ALL) return items
        return items.filter { canonicalDirection(it).equals(filter.name, ignoreCase = true) }
    }

    fun canonicalDirection(item: DynamicMarketScannerClient.Item): String {
        val candidates = listOf(item.trendDirection, item.technicalSignal, item.signal)
        return candidates.asSequence()
            .map { it.trim().uppercase() }
            .firstOrNull { it in setOf("LONG", "SHORT", "WATCH", "NEUTRAL") }
            ?: "INVALID"
    }

    fun invalidFailures(): List<DynamicMarketScannerClient.Failure> =
        mutable.value.result?.failures.orEmpty()

    internal fun resetForTests() = clear()
}
