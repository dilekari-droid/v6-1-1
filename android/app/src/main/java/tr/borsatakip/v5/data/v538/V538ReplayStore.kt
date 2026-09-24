package tr.borsatakip.v5.data.v538

import android.content.Context
import java.security.MessageDigest
import tr.borsatakip.v5.analysis.v538.V538ReplayEntry
import tr.borsatakip.v5.analysis.v538.V538ReplayProtection
import tr.borsatakip.v5.analysis.v538.V538ReplayReport
import tr.borsatakip.v5.analysis.v538.V538ReplayState
import tr.borsatakip.v5.analysis.v538.V538ReplayToken

/** Persistent replay state scoped by provider + key + key generation. */
class V538ReplayStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("v538_replay_guard", Context.MODE_PRIVATE)

    fun validateAndCommit(
        token: V538ReplayToken,
        trustedNowMillis: Long,
        allowedClockSkewMs: Long,
        replayWindowMs: Long = V538ReplayProtection.DEFAULT_REPLAY_WINDOW_MS
    ): V538ReplayReport = synchronized(PROCESS_LOCK) {
        val state = read(token, trustedNowMillis, replayWindowMs)
        val report = V538ReplayProtection.validate(token, state, trustedNowMillis, allowedClockSkewMs, replayWindowMs)
        if (!report.accepted) return@synchronized report
        val next = V538ReplayProtection.nextState(token, state, trustedNowMillis, replayWindowMs)
        val committed = write(token, next)
        if (!committed) V538ReplayReport(false, listOf("REPLAY_STATE_PERSIST_FAILED")) else report
    }

    private fun read(token: V538ReplayToken, nowMillis: Long, replayWindowMs: Long): V538ReplayState {
        val prefix = keyPrefix(token.providerId, token.attestationKeyId, token.attestationKeyGeneration)
        val cutoff = nowMillis - replayWindowMs
        return V538ReplayState(
            currentEpochFingerprint = prefs.getString("${prefix}_epoch", null),
            currentEpochCreatedAt = prefs.getLong("${prefix}_epoch_created", 0L),
            highestEpochCreatedAt = prefs.getLong("${prefix}_highest_epoch_created", 0L),
            highestSequence = prefs.getLong("${prefix}_seq", 0L),
            nonces = decodeEntries(prefs.getStringSet("${prefix}_nonce", emptySet()).orEmpty(), cutoff),
            requestIds = decodeEntries(prefs.getStringSet("${prefix}_req", emptySet()).orEmpty(), cutoff),
            snapshotIds = decodeEntries(prefs.getStringSet("${prefix}_snap", emptySet()).orEmpty(), cutoff),
            retiredEpochs = decodeEntries(prefs.getStringSet("${prefix}_retired_epoch", emptySet()).orEmpty(), cutoff)
        )
    }

    private fun write(token: V538ReplayToken, state: V538ReplayState): Boolean {
        val prefix = keyPrefix(token.providerId, token.attestationKeyId, token.attestationKeyGeneration)
        return prefs.edit()
            .putString("${prefix}_epoch", state.currentEpochFingerprint)
            .putLong("${prefix}_epoch_created", state.currentEpochCreatedAt)
            .putLong("${prefix}_highest_epoch_created", state.highestEpochCreatedAt)
            .putLong("${prefix}_seq", state.highestSequence)
            .putStringSet("${prefix}_nonce", encodeEntries(state.nonces))
            .putStringSet("${prefix}_req", encodeEntries(state.requestIds))
            .putStringSet("${prefix}_snap", encodeEntries(state.snapshotIds))
            .putStringSet("${prefix}_retired_epoch", encodeEntries(state.retiredEpochs))
            .commit()
    }

    private fun encodeEntries(entries: List<V538ReplayEntry>): Set<String> =
        entries.sortedBy { it.acceptedAt }.mapTo(linkedSetOf()) { "${it.acceptedAt}|${it.fingerprint}" }

    private fun decodeEntries(values: Set<String>, cutoff: Long): List<V538ReplayEntry> =
        values.mapNotNull { encoded ->
            val idx = encoded.indexOf('|')
            if (idx <= 0 || idx == encoded.lastIndex) return@mapNotNull null
            val at = encoded.substring(0, idx).toLongOrNull() ?: return@mapNotNull null
            val fp = encoded.substring(idx + 1)
            if (at < cutoff || !fp.matches(Regex("[0-9a-f]{64}"))) null else V538ReplayEntry(fp, at)
        }.sortedBy { it.acceptedAt }

    private fun keyPrefix(providerId: String, keyId: String, generation: Long): String {
        val normalized = "${providerId.trim().lowercase()}|${keyId.trim()}|$generation"
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return "k_" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private companion object { val PROCESS_LOCK = Any() }
}
