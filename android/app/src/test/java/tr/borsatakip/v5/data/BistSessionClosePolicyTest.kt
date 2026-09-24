package tr.borsatakip.v5.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class BistSessionClosePolicyTest {
    private val zone = ZoneId.of("Europe/Istanbul")

    private fun ms(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun afterClose_acceptsOnlySameDayClosedBar() {
        val now = ms("2026-09-21T21:00:00")
        val sameDay = BistSessionClosePolicy.validateLastClosedBar(
            lastBarTimestamp = ms("2026-09-21T17:55:00"),
            frameMs = 5 * 60_000L,
            nowMs = now
        )
        assertTrue(sameDay.accepted)
        assertEquals(BistSessionClosePolicy.Phase.AFTER_CLOSE, sameDay.phase)

        val old = BistSessionClosePolicy.validateLastClosedBar(
            lastBarTimestamp = ms("2026-09-18T17:55:00"),
            frameMs = 5 * 60_000L,
            nowMs = now
        )
        assertFalse(old.accepted)
    }

    @Test
    fun openSession_rejectsSessionCloseMode() {
        val verdict = BistSessionClosePolicy.validateLastClosedBar(
            lastBarTimestamp = ms("2026-09-21T13:55:00"),
            frameMs = 5 * 60_000L,
            nowMs = ms("2026-09-21T14:30:00")
        )
        assertFalse(verdict.accepted)
        assertEquals(BistSessionClosePolicy.Phase.OPEN, verdict.phase)
    }

    @Test
    fun beforeOpenAndWeekend_acceptLatestCompletedTradingSession() {
        val beforeOpen = BistSessionClosePolicy.validateLastClosedBar(
            lastBarTimestamp = ms("2026-09-18T17:55:00"),
            frameMs = 5 * 60_000L,
            nowMs = ms("2026-09-21T09:00:00")
        )
        assertTrue(beforeOpen.accepted)
        assertEquals(BistSessionClosePolicy.Phase.BEFORE_OPEN, beforeOpen.phase)

        val weekend = BistSessionClosePolicy.validateLastClosedBar(
            lastBarTimestamp = ms("2026-09-18T17:55:00"),
            frameMs = 5 * 60_000L,
            nowMs = ms("2026-09-20T21:00:00")
        )
        assertTrue(weekend.accepted)
        assertEquals(BistSessionClosePolicy.Phase.WEEKEND, weekend.phase)
    }
}
