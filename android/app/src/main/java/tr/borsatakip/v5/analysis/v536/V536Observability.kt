package tr.borsatakip.v5.analysis.v536

enum class V536RemoteEventType {
    REQUEST_STARTED,
    HTTP_SUCCESS,
    HTTP_FAILURE,
    CONTRACT_REJECTED,
    ATTESTATION_VERIFIED,
    ATTESTATION_REJECTED,
    REPLAY_REJECTED,
    SNAPSHOT_ACCEPTED
}

data class V536RemoteEvent(
    val type: V536RemoteEventType,
    val atMillis: Long,
    val code: String,
    val engineVersion: String,
    val providerId: String? = null,
    val timeframeMinutes: Int? = null
)

/**
 * Core privacy-safe observability accumulator. It stores counters/codes only;
 * no symbol, API key, payload, price or account data is recorded.
 */
class V536RemoteObservability {
    private val counters = linkedMapOf<V536RemoteEventType, Long>()
    private var last: V536RemoteEvent? = null

    @Synchronized
    fun record(event: V536RemoteEvent) {
        counters[event.type] = (counters[event.type] ?: 0L) + 1L
        last = event
    }

    @Synchronized
    fun count(type: V536RemoteEventType): Long = counters[type] ?: 0L

    @Synchronized
    fun lastEvent(): V536RemoteEvent? = last
}
