package tr.borsatakip.v5.data

/**
 * BIST readiness sonucunda canlı quote eski olduğunda bunun gerçek bir provider arızası mı,
 * yoksa seans dışında beklenen durum mu olduğunu ayırır.
 *
 * Güvenlik ilkesi: yalnız BIST-only kontrolde, history doğrulanmışsa, backend açıkça
 * DELAYED_ANALYSIS_AVAILABLE + STALE_DATA bildiriyorsa ve BIST seansı açık değilse
 * kapanış moduna izin verilir. Ağ/auth/history/başka quote hataları bu yolla gizlenmez.
 */
object BistPreflightPolicy {
    fun allowSessionClose(
        includeViop: Boolean,
        historyOk: Boolean,
        quoteOk: Boolean,
        providerAnalysisMode: String,
        quoteCode: String,
        phase: BistSessionClosePolicy.Phase
    ): Boolean {
        if (includeViop) return false
        if (!historyOk) return false
        if (quoteOk) return false
        if (!providerAnalysisMode.equals("DELAYED_ANALYSIS_AVAILABLE", ignoreCase = true)) return false
        if (!quoteCode.equals("STALE_DATA", ignoreCase = true)) return false
        return phase != BistSessionClosePolicy.Phase.OPEN
    }
}
