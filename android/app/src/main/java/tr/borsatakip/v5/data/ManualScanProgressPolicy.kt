package tr.borsatakip.v5.data

/**
 * Canonical display policy for manual-scan progress.
 * The underlying processed/total counters remain factual; only presentation is capped at 99%
 * until a completed terminal state exists.
 */
object ManualScanProgressPolicy {
    private val completedStates = setOf(ManualScanStatus.COMPLETED, ManualScanStatus.PARTIAL)

    fun displayPercent(session: ManualScanSession): Int {
        val real = session.progress.coerceIn(0, 100)
        return if (session.status in completedStates) real else real.coerceAtMost(99)
    }
}
