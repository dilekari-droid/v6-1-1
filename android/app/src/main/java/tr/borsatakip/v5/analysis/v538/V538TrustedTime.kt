package tr.borsatakip.v5.analysis.v538

/** Provider/key-generation scoped trusted time. */
data class V538TrustedTimeKey(
    val providerId: String,
    val keyId: String,
    val keyGeneration: Long
) {
    init {
        require(providerId.isNotBlank()) { "providerId required" }
        require(keyId.isNotBlank()) { "keyId required" }
        require(keyGeneration > 0L) { "keyGeneration must be positive" }
    }
}

/**
 * Ties a verified backend wall clock to monotonic elapsedRealtime().
 * Anchors are isolated by provider + attestation key generation.
 */
object V538TrustedTimeRegistry {
    private data class Anchor(
        val serverTimeMillis: Long,
        val elapsedRealtimeMillis: Long,
        val serverEpoch: String
    )

    private val anchors = linkedMapOf<V538TrustedTimeKey, Anchor>()

    @Synchronized
    fun now(key: V538TrustedTimeKey, bootstrapNowMillis: Long, elapsedRealtimeMillis: Long): Long {
        require(bootstrapNowMillis > 0L) { "bootstrapNowMillis must be positive" }
        require(elapsedRealtimeMillis >= 0L) { "elapsedRealtimeMillis must be non-negative" }
        val anchor = anchors[key] ?: return bootstrapNowMillis
        if (elapsedRealtimeMillis < anchor.elapsedRealtimeMillis) return bootstrapNowMillis
        return anchor.serverTimeMillis + (elapsedRealtimeMillis - anchor.elapsedRealtimeMillis)
    }

    @Synchronized
    fun markVerified(
        key: V538TrustedTimeKey,
        serverEpoch: String,
        serverTimeMillis: Long,
        elapsedRealtimeMillis: Long
    ) {
        require(serverEpoch.isNotBlank()) { "serverEpoch required" }
        require(serverTimeMillis > 0L) { "serverTimeMillis must be positive" }
        require(elapsedRealtimeMillis >= 0L) { "elapsedRealtimeMillis must be non-negative" }
        anchors[key] = Anchor(serverTimeMillis, elapsedRealtimeMillis, serverEpoch)
    }

    @Synchronized
    internal fun resetForTests() = anchors.clear()
}
