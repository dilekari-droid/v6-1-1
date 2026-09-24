package tr.borsatakip.v5.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreLegacyBackendPolicyTest {
    @Test fun onlyExplicitRetiredOriginsAreRejected() {
        val retired = setOf("retired.example.com")
        assertTrue(SettingsStore.isRetiredBackendUrl("https://retired.example.com", retired))
        assertFalse(SettingsStore.isRetiredBackendUrl("https://active.up.railway.app", retired))
        assertFalse(SettingsStore.isRetiredBackendUrl("https://example.onrender.com", retired))
        assertFalse(SettingsStore.isRetiredBackendUrl("", retired))
    }

    @Test fun emptyRetiredListDoesNotBlankAValidProductionHost() {
        assertFalse(SettingsStore.isRetiredBackendUrl("https://active.up.railway.app", emptySet()))
    }
}
