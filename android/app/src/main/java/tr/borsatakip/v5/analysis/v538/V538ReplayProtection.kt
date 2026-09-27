package tr.borsatakip.v5.analysis.v538

import java.security.MessageDigest

data class V538ReplayToken(
    val providerId: String,
    val attestationKeyId: String,
    val attestationKeyGeneration: Long,
    val serverEpoch: String,
    val serverEpochCreatedAt: Long,
    val serverSequence: Long,
    val nonce: String,
    val requestId: String,
    val snapshotId: String,
    val issuedAt: Long,
    val serverTime: Long,
    val expiresAt: Long
)

data class V538ReplayEntry(val fingerprint: String, val acceptedAt: Long)

data class V538ReplayState(
    val currentEpochFingerprint: String? = null,
    val currentEpochCreatedAt: Long = 0L,
    val highestEpochCreatedAt: Long = 0L,
    val highestSequence: Long = 0L,
    val nonces: List<V538ReplayEntry> = emptyList(),
    val requestIds: List<V538ReplayEntry> = emptyList(),
    val snapshotIds: List<V538ReplayEntry> = emptyList(),
    val retiredEpochs: List<V538ReplayEntry> = emptyList()
)

data class V538ReplayReport(val accepted: Boolean, val violations: List<String>)

object V538ReplayProtection {
    const val DEFAULT_REPLAY_WINDOW_MS: Long = 15L * 60L * 1000L
    private val EPOCH_ID = Regex("[A-Za-z0-9_-]{22,128}")

    fun validate(
        token: V538ReplayToken,
        state: V538ReplayState,
        trustedNowMillis: Long,
        allowedClockSkewMs: Long,
        replayWindowMs: Long = DEFAULT_REPLAY_WINDOW_MS
    ): V538ReplayReport {
        val v = mutableListOf<String>()
        if (token.providerId.isBlank()) v += "PROVIDER_ID_REQUIRED"
        if (token.attestationKeyId.isBlank()) v += "KEY_ID_REQUIRED"
        if (token.attestationKeyGeneration <= 0L) v += "KEY_GENERATION_INVALID"
        if (!EPOCH_ID.matches(token.serverEpoch)) v += "SERVER_EPOCH_FORMAT_INVALID"
        if (token.serverEpochCreatedAt <= 0L) v += "SERVER_EPOCH_CREATED_AT_INVALID"
        if (token.serverSequence <= 0L) v += "SEQUENCE_INVALID"
        if (token.nonce.isBlank()) v += "NONCE_REQUIRED"
        if (token.requestId.isBlank()) v += "REQUEST_ID_REQUIRED"
        if (token.snapshotId.isBlank()) v += "SNAPSHOT_ID_REQUIRED"
        if (trustedNowMillis <= 0L) v += "TRUSTED_NOW_INVALID"
        if (allowedClockSkewMs !in 0L..30_000L) v += "CLOCK_SKEW_INVALID"
        if (replayWindowMs <= 0L) v += "REPLAY_WINDOW_INVALID"
        if (token.issuedAt <= 0L || token.expiresAt <= token.issuedAt) v += "TOKEN_WINDOW_INVALID"
        if (token.serverTime !in token.issuedAt..token.expiresAt) v += "TOKEN_SERVER_TIME_OUTSIDE_WINDOW"
        if (token.serverEpochCreatedAt > token.issuedAt) v += "EPOCH_CREATED_AFTER_TOKEN_ISSUE"
        if (token.issuedAt > trustedNowMillis + allowedClockSkewMs) v += "REPLAY_TOKEN_NOT_YET_VALID"
        if (token.expiresAt <= trustedNowMillis - allowedClockSkewMs) v += "REPLAY_TOKEN_EXPIRED"
        if (token.serverTime > trustedNowMillis + allowedClockSkewMs) v += "REPLAY_SERVER_TIME_IN_FUTURE"

        val epochFp = fingerprint(token.serverEpoch)
        val currentEpoch = state.currentEpochFingerprint
        val cutoff = trustedNowMillis - replayWindowMs
        val retired = state.retiredEpochs.filter { it.acceptedAt >= cutoff }
        if (currentEpoch != null && epochFp != currentEpoch) {
            if (token.serverEpochCreatedAt <= state.highestEpochCreatedAt) v += "SERVER_EPOCH_ROLLBACK"
            if (retired.any { it.fingerprint == epochFp }) v += "SERVER_EPOCH_REPLAY"
        }
        if (currentEpoch == epochFp) {
            if (token.serverEpochCreatedAt != state.currentEpochCreatedAt) v += "SERVER_EPOCH_CREATED_AT_MISMATCH"
            if (token.serverSequence <= state.highestSequence) v += "SEQUENCE_REPLAY"
        }

        fun seen(entries: List<V538ReplayEntry>, raw: String): Boolean {
            val fp = fingerprint(raw)
            return entries.any { it.acceptedAt >= cutoff && it.fingerprint == fp }
        }
        if (seen(state.nonces, token.nonce)) v += "NONCE_REPLAY"
        if (seen(state.requestIds, token.requestId)) v += "REQUEST_ID_REPLAY"
        if (seen(state.snapshotIds, token.snapshotId)) v += "SNAPSHOT_ID_REPLAY"
        return V538ReplayReport(v.isEmpty(), v.distinct())
    }

    fun nextState(
        token: V538ReplayToken,
        state: V538ReplayState,
        acceptedAt: Long,
        replayWindowMs: Long = DEFAULT_REPLAY_WINDOW_MS
    ): V538ReplayState {
        require(acceptedAt > 0L)
        require(replayWindowMs > 0L)
        val cutoff = acceptedAt - replayWindowMs
        val epochFp = fingerprint(token.serverEpoch)
        val epochChanged = state.currentEpochFingerprint != null && state.currentEpochFingerprint != epochFp

        fun prune(entries: List<V538ReplayEntry>): MutableList<V538ReplayEntry> =
            entries.filterTo(mutableListOf()) { it.acceptedAt >= cutoff }
        fun append(entries: List<V538ReplayEntry>, raw: String): List<V538ReplayEntry> =
            prune(entries).apply {
                val fp = fingerprint(raw)
                removeAll { it.fingerprint == fp }
                add(V538ReplayEntry(fp, acceptedAt))
            }.sortedBy { it.acceptedAt }

        val retired = prune(state.retiredEpochs).apply {
            if (epochChanged) {
                val old = state.currentEpochFingerprint!!
                removeAll { it.fingerprint == old }
                add(V538ReplayEntry(old, acceptedAt))
            }
        }.sortedBy { it.acceptedAt }

        return V538ReplayState(
            currentEpochFingerprint = epochFp,
            currentEpochCreatedAt = token.serverEpochCreatedAt,
            highestEpochCreatedAt = maxOf(state.highestEpochCreatedAt, token.serverEpochCreatedAt),
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
