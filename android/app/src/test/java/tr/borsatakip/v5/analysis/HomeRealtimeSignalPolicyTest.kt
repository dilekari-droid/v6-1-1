package tr.borsatakip.v5.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRealtimeSignalPolicyTest {
    @Test
    fun same_signal_without_meaningful_strength_change_does_not_pulse() {
        val old = HomeRealtimeSignalPolicy.Marker("LONG", 82)
        val same = HomeRealtimeSignalPolicy.Marker("LONG", 85)
        assertFalse(HomeRealtimeSignalPolicy.isMeaningfulChange(old, same))
    }

    @Test
    fun direction_change_or_strength_delta_pulses() {
        assertTrue(
            HomeRealtimeSignalPolicy.isMeaningfulChange(
                HomeRealtimeSignalPolicy.Marker("LONG", 82),
                HomeRealtimeSignalPolicy.Marker("SHORT", 83)
            )
        )
        assertTrue(
            HomeRealtimeSignalPolicy.isMeaningfulChange(
                HomeRealtimeSignalPolicy.Marker("LONG", 82),
                HomeRealtimeSignalPolicy.Marker("LONG", 88)
            )
        )
    }

    @Test
    fun neutral_or_missing_signal_never_pulses() {
        assertFalse(HomeRealtimeSignalPolicy.isMeaningfulChange(null, null))
        assertFalse(HomeRealtimeSignalPolicy.isMeaningfulChange(null, HomeRealtimeSignalPolicy.Marker("NEUTRAL", 70)))
        assertFalse(HomeRealtimeSignalPolicy.isMeaningfulChange(HomeRealtimeSignalPolicy.Marker("LONG", 90), null))
    }
}
