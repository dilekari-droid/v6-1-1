package tr.borsatakip.v5.data

/**
 * Pure, testable resolver for the Android snapshot-batch policy advertised by the backend.
 * Keeps timeout relationships monotonic and bounded without depending on Android framework code.
 */
data class SnapshotBatchPolicy(
    val maxSymbols: Int,
    val readTimeoutMs: Long,
    val callTimeoutMs: Long,
    val outerTimeoutMs: Long
) {
    companion object {
        const val DEFAULT_MAX_SYMBOLS = 20
        const val DEFAULT_READ_TIMEOUT_MS = 175_000L
        const val DEFAULT_CALL_TIMEOUT_MS = 180_000L
        const val DEFAULT_OUTER_TIMEOUT_MS = 185_000L

        fun resolve(
            maxSymbols: Int?,
            readTimeoutMs: Long?,
            callTimeoutMs: Long?,
            outerTimeoutMs: Long?
        ): SnapshotBatchPolicy {
            val resolvedMaxSymbols = (maxSymbols ?: DEFAULT_MAX_SYMBOLS).coerceIn(1, 100)
            val read = (readTimeoutMs ?: DEFAULT_READ_TIMEOUT_MS).coerceIn(30_000L, 600_000L)
            val call = (callTimeoutMs ?: DEFAULT_CALL_TIMEOUT_MS).coerceIn(read, 600_000L)
            val outer = (outerTimeoutMs ?: DEFAULT_OUTER_TIMEOUT_MS).coerceIn(call, 650_000L)
            return SnapshotBatchPolicy(resolvedMaxSymbols, read, call, outer)
        }
    }
}
