package tr.borsatakip.v5.analysis.v536

data class V536ReplayToken(
    val providerId: String,
    val serverSequence: Long,
    val nonce: String,
    val requestId: String,
    val snapshotId: String,
    val expiresAt: Long
)

data class V536ReplayState(
    val highestSequence: Long = 0L,
    val nonces: Set<String> = emptySet(),
    val requestIds: Set<String> = emptySet(),
    val snapshotIds: Set<String> = emptySet()
)

data class V536ReplayReport(val accepted: Boolean, val violations: List<String>)

object V536ReplayProtection {
    fun validate(token: V536ReplayToken, state: V536ReplayState, serverTime: Long): V536ReplayReport {
        val v = mutableListOf<String>()
        if (token.providerId.isBlank()) v += "PROVIDER_ID_REQUIRED"
        if (token.serverSequence <= 0L) v += "SEQUENCE_INVALID"
        if (token.serverSequence <= state.highestSequence) v += "SEQUENCE_REPLAY"
        if (token.nonce.isBlank()) v += "NONCE_REQUIRED"
        if (token.requestId.isBlank()) v += "REQUEST_ID_REQUIRED"
        if (token.snapshotId.isBlank()) v += "SNAPSHOT_ID_REQUIRED"
        if (token.nonce in state.nonces) v += "NONCE_REPLAY"
        if (token.requestId in state.requestIds) v += "REQUEST_ID_REPLAY"
        if (token.snapshotId in state.snapshotIds) v += "SNAPSHOT_ID_REPLAY"
        if (token.expiresAt <= serverTime) v += "REPLAY_TOKEN_EXPIRED"
        return V536ReplayReport(v.isEmpty(), v.distinct())
    }

    fun nextState(token: V536ReplayToken, state: V536ReplayState, maxEntries: Int = 64): V536ReplayState {
        fun bounded(values: Set<String>, newValue: String): Set<String> = (values.toList().takeLast((maxEntries - 1).coerceAtLeast(0)) + newValue).toSet()
        return V536ReplayState(
            highestSequence = maxOf(state.highestSequence, token.serverSequence),
            nonces = bounded(state.nonces, token.nonce),
            requestIds = bounded(state.requestIds, token.requestId),
            snapshotIds = bounded(state.snapshotIds, token.snapshotId)
        )
    }
}
