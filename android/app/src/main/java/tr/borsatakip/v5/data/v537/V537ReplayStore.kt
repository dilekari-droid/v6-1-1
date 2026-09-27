package tr.borsatakip.v5.data.v537

import android.content.Context
import java.security.MessageDigest
import tr.borsatakip.v5.analysis.v537.V537ReplayEntry
import tr.borsatakip.v5.analysis.v537.V537ReplayProtection
import tr.borsatakip.v5.analysis.v537.V537ReplayReport
import tr.borsatakip.v5.analysis.v537.V537ReplayState
import tr.borsatakip.v5.analysis.v537.V537ReplayToken

/**
 * Persistent replay state per provider.
 *
 * - process-wide lock prevents two clients accepting the same token concurrently
 * - identifiers are persisted only as SHA-256 fingerprints
 * - entries are TTL based; no Set iteration order is used as an age signal
 * - commit() is deliberate: accepted replay state is durable before return
 */
class V537ReplayStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("v537_replay_guard", Context.MODE_PRIVATE)

    fun validateAndCommit(
        token: V537ReplayToken,
        trustedNowMillis: Long,
        allowedClockSkewMs: Long,
        replayWindowMs: Long = V537ReplayProtection.DEFAULT_REPLAY_WINDOW_MS
    ): V537ReplayReport = synchronized(PROCESS_LOCK) {
        val state = read(token.providerId, trustedNowMillis, replayWindowMs)
        val report = V537ReplayProtection.validate(token, state, trustedNowMillis, allowedClockSkewMs, replayWindowMs)
        if (!report.accepted) return@synchronized report
        val next = V537ReplayProtection.nextState(token, state, trustedNowMillis, replayWindowMs)
        val committed = write(token.providerId, next)
        if (!committed) V537ReplayReport(false, listOf("REPLAY_STATE_PERSIST_FAILED")) else report
    }

    private fun read(providerId: String, nowMillis: Long, replayWindowMs: Long): V537ReplayState {
        val prefix = keyPrefix(providerId)
        val cutoff = nowMillis - replayWindowMs
        return V537ReplayState(
            currentEpochFingerprint = prefs.getString("${prefix}_epoch", null),
            highestSequence = prefs.getLong("${prefix}_seq", 0L),
            nonces = decodeEntries(prefs.getStringSet("${prefix}_nonce", emptySet()).orEmpty(), cutoff),
            requestIds = decodeEntries(prefs.getStringSet("${prefix}_req", emptySet()).orEmpty(), cutoff),
            snapshotIds = decodeEntries(prefs.getStringSet("${prefix}_snap", emptySet()).orEmpty(), cutoff),
            retiredEpochs = decodeEntries(prefs.getStringSet("${prefix}_retired_epoch", emptySet()).orEmpty(), cutoff)
        )
    }

    private fun write(providerId: String, state: V537ReplayState): Boolean {
        val prefix = keyPrefix(providerId)
        return prefs.edit()
            .putString("${prefix}_epoch", state.currentEpochFingerprint)
            .putLong("${prefix}_seq", state.highestSequence)
            .putStringSet("${prefix}_nonce", encodeEntries(state.nonces))
            .putStringSet("${prefix}_req", encodeEntries(state.requestIds))
            .putStringSet("${prefix}_snap", encodeEntries(state.snapshotIds))
            .putStringSet("${prefix}_retired_epoch", encodeEntries(state.retiredEpochs))
            .commit()
    }

    private fun encodeEntries(entries: List<V537ReplayEntry>): Set<String> =
        entries.mapTo(linkedSetOf()) { "${it.acceptedAt}|${it.fingerprint}" }

    private fun decodeEntries(values: Set<String>, cutoff: Long): List<V537ReplayEntry> =
        values.mapNotNull { encoded ->
            val idx = encoded.indexOf('|')
            if (idx <= 0 || idx == encoded.lastIndex) return@mapNotNull null
            val at = encoded.substring(0, idx).toLongOrNull() ?: return@mapNotNull null
            val fp = encoded.substring(idx + 1)
            if (at < cutoff || !fp.matches(Regex("[0-9a-f]{64}"))) null else V537ReplayEntry(fp, at)
        }.sortedBy { it.acceptedAt }

    private fun keyPrefix(providerId: String): String {
        val normalized = providerId.trim().lowercase()
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return "p_" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val PROCESS_LOCK = Any()
    }
}
