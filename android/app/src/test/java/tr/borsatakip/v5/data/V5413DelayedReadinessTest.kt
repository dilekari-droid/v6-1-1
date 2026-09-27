package tr.borsatakip.v5.data

import org.junit.Assert.*
import org.junit.Test

class V5413DelayedReadinessTest {
    @Test fun openSessionStaleQuoteDoesNotBecomeRealtime() {
        assertFalse(BistPreflightPolicy.allowSessionClose(false,true,false,"DELAYED_ANALYSIS_AVAILABLE","STALE_DATA",BistSessionClosePolicy.Phase.OPEN))
    }

    @Test fun realtimeThresholdIsNotRelaxedInClientPolicy() {
        assertFalse(BistPreflightPolicy.allowSessionClose(false,true,true,"REALTIME","LIVE",BistSessionClosePolicy.Phase.OPEN))
    }
}
