package tr.borsatakip.v5.data.v536

import android.content.Context
import java.security.MessageDigest
import tr.borsatakip.v5.analysis.v536.V536ReplayProtection
import tr.borsatakip.v5.analysis.v536.V536ReplayReport
import tr.borsatakip.v5.analysis.v536.V536ReplayState
import tr.borsatakip.v5.analysis.v536.V536ReplayToken

/**
 * Small persistent replay window per provider. Values are opaque identifiers;
 * no market payload or credential is persisted here.
 *
 * Replay validation and persistence are guarded by a process-wide lock so two
 * scanner/client instances cannot validate the same token concurrently. commit()
 * is intentional here: a successfully accepted token must be durably recorded
 * before the caller continues.
 */
class V536ReplayStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("v536_replay_guard", Context.MODE_PRIVATE)

    fun validateAndCommit(token: V536ReplayToken, serverTime: Long): V536ReplayReport = synchronized(PROCESS_LOCK) {
        val state = read(token.providerId)
        val report = V536ReplayProtection.validate(token, state, serverTime)
        if (!report.accepted) return@synchronized report
        val committed = write(token.providerId, V536ReplayProtection.nextState(token, state))
        if (!committed) V536ReplayReport(false, listOf("REPLAY_STATE_PERSIST_FAILED")) else report
    }

    private fun read(providerId: String): V536ReplayState {
        val prefix = keyPrefix(providerId)
        return V536ReplayState(
            highestSequence = prefs.getLong("${prefix}_seq", 0L),
            nonces = prefs.getStringSet("${prefix}_nonce", emptySet())?.toSet().orEmpty(),
            requestIds = prefs.getStringSet("${prefix}_req", emptySet())?.toSet().orEmpty(),
            snapshotIds = prefs.getStringSet("${prefix}_snap", emptySet())?.toSet().orEmpty()
        )
    }

    private fun write(providerId: String, state: V536ReplayState): Boolean {
        val prefix = keyPrefix(providerId)
        return prefs.edit()
            .putLong("${prefix}_seq", state.highestSequence)
            .putStringSet("${prefix}_nonce", state.nonces)
            .putStringSet("${prefix}_req", state.requestIds)
            .putStringSet("${prefix}_snap", state.snapshotIds)
            .commit()
    }

    private fun keyPrefix(providerId: String): String {
        val normalized = providerId.trim().lowercase()
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return "p_" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val PROCESS_LOCK = Any()
    }
}
