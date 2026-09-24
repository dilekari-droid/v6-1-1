package tr.borsatakip.v5.data

/**
 * Source-of-truth presentation semantics for market-data state, provider status and data age.
 *
 * This class intentionally has no Android dependency so boundary behavior can be verified without
 * producing an APK. It does not infer market truth from UI state; it only presents the state already
 * established by the provider/data-quality layer.
 */
enum class UnifiedMarketPresentationState(val label: String) {
    LIVE("CANLI"),
    STALE("ESKİ"),
    PARTIAL("KISMİ"),
    UNAVAILABLE("KULLANILAMIYOR")
}

enum class DataAgeBand {
    FRESH,
    AGED,
    EXPIRED,
    UNKNOWN
}

data class ProviderStatusPresentation(
    val providerId: String,
    val source: String,
    val state: UnifiedMarketPresentationState,
    val stateLabel: String,
    val dataAgeLabel: String
) {
    fun compact(): String {
        val identity = source.trim().ifBlank { providerId.trim().ifBlank { "Provider" } }
        return "$identity • $stateLabel • yaş $dataAgeLabel"
    }
}

object MarketPresentationPolicy {
    const val FRESH_MAX_AGE_MS = 90_000L
    const val KNOWN_MAX_AGE_MS = 4L * 24L * 60L * 60L * 1000L

    fun state(rawState: String?): UnifiedMarketPresentationState = when (rawState.orEmpty().trim().uppercase()) {
        "LIVE" -> UnifiedMarketPresentationState.LIVE
        "STALE" -> UnifiedMarketPresentationState.STALE
        "DELAYED", "FALLBACK", "PARTIAL" -> UnifiedMarketPresentationState.PARTIAL
        "OFFLINE", "UNKNOWN", "UNAVAILABLE", "" -> UnifiedMarketPresentationState.UNAVAILABLE
        else -> UnifiedMarketPresentationState.UNAVAILABLE
    }

    fun dataAgeBand(ageMs: Long?): DataAgeBand = when {
        ageMs == null || ageMs < 0L -> DataAgeBand.UNKNOWN
        ageMs <= FRESH_MAX_AGE_MS -> DataAgeBand.FRESH
        ageMs <= KNOWN_MAX_AGE_MS -> DataAgeBand.AGED
        else -> DataAgeBand.EXPIRED
    }

    fun formatDataAge(ageMs: Long?): String {
        if (ageMs == null || ageMs < 0L) return "bilinmiyor"
        return when {
            ageMs < 1_000L -> "<1 sn"
            ageMs < 60_000L -> "${ageMs / 1_000L} sn"
            ageMs < 3_600_000L -> "${ageMs / 60_000L} dk"
            ageMs < 86_400_000L -> "${ageMs / 3_600_000L} sa"
            else -> "${ageMs / 86_400_000L} gün"
        }
    }

    fun providerStatus(
        providerId: String,
        source: String,
        rawState: String?,
        dataAgeMs: Long?
    ): ProviderStatusPresentation {
        val unified = state(rawState)
        return ProviderStatusPresentation(
            providerId = providerId,
            source = source,
            state = unified,
            stateLabel = unified.label,
            dataAgeLabel = formatDataAge(dataAgeMs)
        )
    }
}
