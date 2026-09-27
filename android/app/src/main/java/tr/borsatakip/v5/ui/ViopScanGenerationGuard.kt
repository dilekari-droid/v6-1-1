package tr.borsatakip.v5.ui

/** Rejects late async scan responses so an older scan can never overwrite a newer one. */
class ViopScanGenerationGuard {
    private var current: Long = 0L

    fun next(): Long {
        current += 1L
        return current
    }

    fun accepts(token: Long): Boolean = token == current
}
