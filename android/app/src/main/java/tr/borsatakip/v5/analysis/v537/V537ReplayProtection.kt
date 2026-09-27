package tr.borsatakip.v5.analysis.v537

import java.security.MessageDigest

data class V537ReplayToken(
    val providerId: String,
    val serverEpoch: String,
    val serverSequence: Long,
    val nonce: String,
    val requestId: String,
    val snapshotId: String,
    val expiresAt: Long
)

data class V537ReplayEntry(val fingerprint: String, val acceptedAt: Long)

data class V537ReplayState(
    val currentEpochFingerprint: String? = null,
    val highestSequence: Long = 0L,
    val nonces: List<V537ReplayEntry> = emptyList(),
    val requestIds: List<V537ReplayEntry> = emptyList(),
    val snapshotIds: List<V537ReplayEntry> = emptyList(),
    val retiredEpochs: List<V537ReplayEntry> = emptyList()
)

data class V537ReplayReport(val accepted: Boolean, val violations: List<String>)

object V537ReplayProtection {
    const val DEFAULT_REPLAY_WINDOW_MS: Long = 15L * 60L * 1000L

    fun validate(
        token: V537ReplayToken,
        state: V537ReplayState,
        trustedNowMillis: Long,
        allowedClockSkewMs: Long,
        replayWindowMs: Long = DEFAULT_REPLAY_WINDOW_MS
    ): V537ReplayReport {
        val v = mutableListOf<String>()
        if (token.providerId.isBlank()) v += "PROVIDER_ID_REQUIRED"
        if (token.serverEpoch.isBlank()) v += "SERVER_EPOCH_REQUIRED"
        if (token.serverSequence <= 0L) v += "SEQUENCE_INVALID"
        if (token.nonce.isBlank()) v += "NONCE_REQUIRED"
        if (token.requestId.isBlank()) v += "REQUEST_ID_REQUIRED"
        if (token.snapshotId.isBlank()) v += "SNAPSHOT_ID_REQUIRED"
        if (trustedNowMillis <= 0L) v += "TRUSTED_NOW_INVALID"
        if (allowedClockSkewMs !in 0L..30_000L) v += "CLOCK_SKEW_INVALID"
        if (replayWindowMs <= 0L) v += "REPLAY_WINDOW_INVALID"

        if (trustedNowMillis > 0L && token.expiresAt < trustedNowMillis - allowedClockSkewMs) v += "REPLAY_TOKEN_EXPIRED"

        val epochFp = fingerprint(token.serverEpoch)
        val currentEpoch = state.currentEpochFingerprint
        val cutoff = trustedNowMillis - replayWindowMs
        val retired = state.retiredEpochs.filter { it.acceptedAt >= cutoff }
        if (currentEpoch != null && epochFp != currentEpoch && retired.any { it.fingerprint == epochFp }) v += "SERVER_EPOCH_REPLAY"
        if (currentEpoch == epochFp && token.serverSequence <= state.highestSequence) v += "SEQUENCE_REPLAY"

        fun seen(entries: List<V537ReplayEntry>, raw: String): Boolean {
            val fp = fingerprint(raw)
            return entries.any { it.acceptedAt >= cutoff && it.fingerprint == fp }
        }
        if (seen(state.nonces, token.nonce)) v += "NONCE_REPLAY"
        if (seen(state.requestIds, token.requestId)) v += "REQUEST_ID_REPLAY"
        if (seen(state.snapshotIds, token.snapshotId)) v += "SNAPSHOT_ID_REPLAY"
        return V537ReplayReport(v.isEmpty(), v.distinct())
    }

    fun nextState(
        token: V537ReplayToken,
        state: V537ReplayState,
        acceptedAt: Long,
        replayWindowMs: Long = DEFAULT_REPLAY_WINDOW_MS
    ): V537ReplayState {
        require(acceptedAt > 0L) { "acceptedAt must be positive" }
        require(replayWindowMs > 0L) { "replayWindowMs must be positive" }
        val cutoff = acceptedAt - replayWindowMs
        val epochFp = fingerprint(token.serverEpoch)
        val epochChanged = state.currentEpochFingerprint != null && state.currentEpochFingerprint != epochFp

        fun prune(entries: List<V537ReplayEntry>): MutableList<V537ReplayEntry> =
            entries.filterTo(mutableListOf()) { it.acceptedAt >= cutoff }

        fun append(entries: List<V537ReplayEntry>, raw: String): List<V537ReplayEntry> =
            prune(entries).apply {
                val fp = fingerprint(raw)
                removeAll { it.fingerprint == fp }
                add(V537ReplayEntry(fp, acceptedAt))
            }

        val retired = prune(state.retiredEpochs).apply {
            if (epochChanged) {
                val old = state.currentEpochFingerprint!!
                removeAll { it.fingerprint == old }
                add(V537ReplayEntry(old, acceptedAt))
            }
        }
        return V537ReplayState(
            currentEpochFingerprint = epochFp,
            highestSequence = if (epochChanged || state.currentEpochFingerprint == null) token.serverSequence else maxOf(state.highestSequence, token.serverSequence),
            nonces = append(state.nonces, token.nonce),
            requestIds = append(state.requestIds, token.requestId),
            snapshotIds = append(state.snapshotIds, token.snapshotId),
            retiredEpochs = retired
        )
    }

    fun fingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
