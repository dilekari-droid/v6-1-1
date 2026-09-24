package tr.borsatakip.v5.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendUrlPolicyTest {
    @Test fun acceptsCleanPublicHttpsOrigin() {
        assertTrue(BackendUrlPolicy.isValidHttps("https://backend.example.com"))
        assertTrue(BackendPreflightClient.validHttps("https://backend.example.com"))
        assertTrue(ProviderReadinessService.isValidHttps("https://backend.example.com"))
    }

    @Test fun rejectsUnsafeOrAmbiguousOrigins() {
        listOf(
            "http://backend.example.com",
            "https://localhost",
            "https://127.0.0.1",
            "https://[::1]",
            "https://10.0.2.2",
            "https://user:pass@backend.example.com",
            "https://backend.example.com?target=other",
            "https://backend.example.com#fragment"
        ).forEach { assertFalse(it, BackendUrlPolicy.isValidHttps(it)) }
    }
}
