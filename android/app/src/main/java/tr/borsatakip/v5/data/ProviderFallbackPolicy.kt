package tr.borsatakip.v5.data

/** Central policy: no screen/worker may bypass the user fallback settings. */
object ProviderFallbackPolicy {
    fun allowed(experimentalProvidersEnabled: Boolean, yahooFallbackEnabled: Boolean): Boolean =
        experimentalProvidersEnabled && yahooFallbackEnabled
}
