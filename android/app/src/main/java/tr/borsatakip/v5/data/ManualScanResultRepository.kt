package tr.borsatakip.v5.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tr.borsatakip.v5.model.Opportunity

/**
 * Process-wide single source for the result list shared by BIST Scan and Opportunity screens.
 * Durable recovery remains LastSuccessfulScanStore; this repository prevents independent UI copies
 * from diverging while the process is alive.
 */
data class ManualScanResultState(
    val items: List<Opportunity> = emptyList(),
    val scanRunId: String? = null,
    val source: String = "NONE",
    val updatedAt: Long = 0L
)

object ManualScanResultRepository {
    private val mutable = MutableStateFlow(ManualScanResultState())
    val state: StateFlow<ManualScanResultState> = mutable.asStateFlow()

    fun snapshot(): ManualScanResultState = mutable.value

    fun publish(
        items: List<Opportunity>,
        scanRunId: String? = items.mapNotNull { it.scanRunId }.distinct().singleOrNull(),
        source: String = "SCAN"
    ) {
        mutable.value = ManualScanResultState(
            items = items.toList(),
            scanRunId = scanRunId,
            source = source,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun clear(source: String = "CLEARED") = publish(emptyList(), scanRunId = null, source = source)

    internal fun resetForTests() {
        mutable.value = ManualScanResultState()
    }
}
