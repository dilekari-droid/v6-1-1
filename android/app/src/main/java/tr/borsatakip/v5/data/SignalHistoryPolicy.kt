package tr.borsatakip.v5.data

import tr.borsatakip.v5.model.ScanRunStatus

object SignalHistoryPolicy {
    fun shouldPersist(status: ScanRunStatus, validCount: Int): Boolean =
        validCount > 0 && (status == ScanRunStatus.COMPLETE || status == ScanRunStatus.PARTIAL)

    fun expectedPersistedCount(status: ScanRunStatus, validCount: Int): Int =
        if (shouldPersist(status, validCount)) validCount.coerceAtLeast(0) else 0

    fun uniqueKey(scanId: String, symbol: String, signalTime: Long): String =
        "${scanId.trim()}:${symbol.trim().uppercase()}:$signalTime"
}
