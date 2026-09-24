package tr.borsatakip.v5.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRetryPolicyTest {
    @Test fun authAndSemanticErrorsAreNotRetried() {
        assertFalse(ProviderRetryPolicy.isRetryable(ProviderException(ProviderFailureCode.AUTH_ERROR, "auth")))
        assertFalse(ProviderRetryPolicy.isRetryable(ProviderException(ProviderFailureCode.BIST_QUOTE_ERROR, "symbol")))
        assertFalse(ProviderRetryPolicy.isRetryable(ProviderException(ProviderFailureCode.BIST_HISTORY_ERROR, "history")))
    }

    @Test fun transientErrorsAreRetried() {
        assertTrue(ProviderRetryPolicy.isRetryable(ProviderException(ProviderFailureCode.NETWORK_TIMEOUT, "timeout")))
        assertTrue(ProviderRetryPolicy.isRetryable(ProviderException(ProviderFailureCode.NETWORK_ERROR, "network")))
        assertTrue(ProviderRetryPolicy.isRetryable(ProviderException(ProviderFailureCode.SERVER_ERROR, "server")))
        assertTrue(ProviderRetryPolicy.isRetryable(ProviderException(ProviderFailureCode.RATE_LIMIT, "rate")))
    }
}
