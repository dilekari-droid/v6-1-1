package tr.borsatakip.v5.ui

import tr.borsatakip.v5.data.ManualScanProgressPolicy
import tr.borsatakip.v5.data.ManualScanSession
import tr.borsatakip.v5.data.ManualScanStatus

/**
 * UI-only projection of the real manual scan state.
 * It never invents progress and never renders 100% before a terminal completed result exists.
 */
object BistScanUiPolicy {
    fun progressPercent(session: ManualScanSession): Int = ManualScanProgressPolicy.displayPercent(session)

    fun progressLabel(session: ManualScanSession): String {
        val total = session.totalCount
        val processed = if (total > 0) session.scannedCount.coerceIn(0, total) else session.scannedCount.coerceAtLeast(0)
        if (total > 0) return "$processed / $total  •  %${progressPercent(session)}"
        return when (session.status) {
            ManualScanStatus.IDLE -> "TARAMA BAŞLAMADI"
            ManualScanStatus.PREFLIGHT -> "DOĞRULANIYOR"
            else -> "$processed / ?"
        }
    }

    fun elapsedLabel(session: ManualScanSession, nowMs: Long = System.currentTimeMillis()): String {
        if (session.startedAt <= 0L) return "Süre: —"
        val end = session.completedAt ?: session.lastUpdate.takeIf { it > 0L } ?: nowMs
        val elapsedSeconds = ((end - session.startedAt).coerceAtLeast(0L) / 1000L)
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        return if (minutes > 0) "Süre: ${minutes} dk ${seconds} sn" else "Süre: ${seconds} sn"
    }
}
