package tr.borsatakip.v5.worker

import tr.borsatakip.v5.model.ScanRunStatus

/** Pure lifecycle policy so normal/manual/external shutdown semantics are unit-testable. */
object BistScanServiceLifecyclePolicy {
    fun shouldMarkInterrupted(
        normalShutdown: Boolean,
        terminalStatePublished: Boolean,
        jobActive: Boolean,
        stopRequested: Boolean
    ): Boolean = !normalShutdown && !terminalStatePublished && jobActive && !stopRequested

    /** COMPLETED is legal only after a COMPLETE scanner run has durable, verified result persistence. */
    fun canPublishCompleted(runStatus: ScanRunStatus?, persistenceSucceeded: Boolean): Boolean =
        runStatus == ScanRunStatus.COMPLETE && persistenceSucceeded
}
