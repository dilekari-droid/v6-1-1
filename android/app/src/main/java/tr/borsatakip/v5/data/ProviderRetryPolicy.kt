package tr.borsatakip.v5.data

/** Central retry policy: semantic/auth/config failures are never retried. */
object ProviderRetryPolicy {
    fun isRetryable(t: Throwable): Boolean {
        val pe = t as? ProviderException ?: return true
        return pe.code in setOf(
            ProviderFailureCode.NETWORK_TIMEOUT,
            ProviderFailureCode.NETWORK_ERROR,
            ProviderFailureCode.SERVER_ERROR,
            ProviderFailureCode.RATE_LIMIT,
            ProviderFailureCode.EMPTY_DATA
        )
    }
}
