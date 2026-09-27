package tr.borsatakip.v5.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BistPreflightPolicyTest {
    @Test
    fun afterClose_allowsVerifiedDelayedHistory() {
        assertTrue(
            BistPreflightPolicy.allowSessionClose(
                includeViop = false,
                historyOk = true,
                quoteOk = false,
                providerAnalysisMode = "DELAYED_ANALYSIS_AVAILABLE",
                quoteCode = "STALE_DATA",
                phase = BistSessionClosePolicy.Phase.AFTER_CLOSE
            )
        )
    }

    @Test
    fun openSession_neverUsesSessionCloseEscape() {
        assertFalse(
            BistPreflightPolicy.allowSessionClose(
                includeViop = false,
                historyOk = true,
                quoteOk = false,
                providerAnalysisMode = "DELAYED_ANALYSIS_AVAILABLE",
                quoteCode = "STALE_DATA",
                phase = BistSessionClosePolicy.Phase.OPEN
            )
        )
    }

    @Test
    fun doesNotMaskHistoryOrQuoteErrors() {
        assertFalse(
            BistPreflightPolicy.allowSessionClose(
                includeViop = false,
                historyOk = false,
                quoteOk = false,
                providerAnalysisMode = "DELAYED_ANALYSIS_AVAILABLE",
                quoteCode = "STALE_DATA",
                phase = BistSessionClosePolicy.Phase.AFTER_CLOSE
            )
        )
        assertFalse(
            BistPreflightPolicy.allowSessionClose(
                includeViop = false,
                historyOk = true,
                quoteOk = false,
                providerAnalysisMode = "DELAYED_ANALYSIS_AVAILABLE",
                quoteCode = "UPSTREAM_ERROR",
                phase = BistSessionClosePolicy.Phase.AFTER_CLOSE
            )
        )
    }

    @Test
    fun viopRemainsStrict() {
        assertFalse(
            BistPreflightPolicy.allowSessionClose(
                includeViop = true,
                historyOk = true,
                quoteOk = false,
                providerAnalysisMode = "DELAYED_ANALYSIS_AVAILABLE",
                quoteCode = "STALE_DATA",
                phase = BistSessionClosePolicy.Phase.AFTER_CLOSE
            )
        )
    }
}
