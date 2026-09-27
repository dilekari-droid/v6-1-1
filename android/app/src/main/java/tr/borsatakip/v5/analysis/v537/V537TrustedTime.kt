package tr.borsatakip.v5.analysis.v537

/**
 * Process-wide trusted-time anchor.
 *
 * A verified server wall clock is tied to Android elapsedRealtime() so normal
 * device wall-clock changes do not move the trust window during the process.
 * Before the first verified envelope, an HTTPS Date/client wall-clock bootstrap
 * value supplied by the caller is used fail-closed by attestation checks.
 */
object V537TrustedTimeAnchor {
    private data class Anchor(val serverTimeMillis: Long, val elapsedRealtimeMillis: Long)
    private var anchor: Anchor? = null

    @Synchronized
    fun now(bootstrapNowMillis: Long, elapsedRealtimeMillis: Long): Long {
        val a = anchor
        if (a == null || elapsedRealtimeMillis < a.elapsedRealtimeMillis) return bootstrapNowMillis
        val delta = elapsedRealtimeMillis - a.elapsedRealtimeMillis
        return a.serverTimeMillis + delta
    }

    @Synchronized
    fun markVerified(serverTimeMillis: Long, elapsedRealtimeMillis: Long) {
        require(serverTimeMillis > 0L) { "serverTimeMillis must be positive" }
        require(elapsedRealtimeMillis >= 0L) { "elapsedRealtimeMillis must be non-negative" }
        anchor = Anchor(serverTimeMillis, elapsedRealtimeMillis)
    }

    @Synchronized
    internal fun resetForTests() {
        anchor = null
    }

}
