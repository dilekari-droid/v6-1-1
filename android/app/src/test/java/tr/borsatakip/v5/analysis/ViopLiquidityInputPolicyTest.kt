package tr.borsatakip.v5.analysis

import org.junit.Assert.*
import org.junit.Test

class ViopLiquidityInputPolicyTest {
    private fun resolve(
        qv: Double? = null,
        cv: Double? = null,
        bar: Double? = null,
        qoi: Long? = null,
        coi: Long? = null
    ) = ViopLiquidityInputPolicy.resolve(qv, cv, bar, qoi, coi, 1.0, 1L)

    @Test fun quote_volume_has_priority() {
        val r = resolve(qv = 120.0, cv = 80.0, bar = 40.0, qoi = 9)
        assertEquals(120.0, r.volume!!, 0.0)
        assertEquals(ViopLiquidityInputPolicy.VolumeSource.QUOTE, r.volumeSource)
        assertNull(r.rejectCode)
    }

    @Test fun verified_closed_bar_volume_is_valid_fallback() {
        val r = resolve(bar = 44.0)
        assertEquals(44.0, r.volume!!, 0.0)
        assertEquals(ViopLiquidityInputPolicy.VolumeSource.LAST_CLOSED_BAR, r.volumeSource)
        assertNull(r.rejectCode)
    }

    @Test fun missing_open_interest_is_not_fabricated_or_hard_rejected() {
        val r = resolve(bar = 55.0)
        assertNull(r.openInterest)
        assertFalse(r.openInterestAvailable)
        assertNull(r.rejectCode)
    }

    @Test fun open_interest_is_used_when_provider_supplies_it() {
        val r = resolve(bar = 55.0, qoi = 1234L)
        assertEquals(1234L, r.openInterest)
        assertTrue(r.openInterestAvailable)
    }

    @Test fun no_real_volume_remains_fail_closed() {
        val r = resolve(qv = null, cv = 0.0, bar = 0.0, qoi = 10L)
        assertEquals("LOW_LIQUIDITY", r.rejectCode)
        assertNull(r.volume)
        assertEquals(ViopLiquidityInputPolicy.VolumeSource.NONE, r.volumeSource)
    }
}
