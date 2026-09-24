package tr.borsatakip.v5.ui

import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.data.ManualScanResultRepository
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopOpportunity
import tr.borsatakip.v5.scan.ScanState

object AppSession {
    var lastOpportunities: List<Opportunity>
        get() = ManualScanResultRepository.snapshot().items
        set(value) { ManualScanResultRepository.publish(value, source = "APP_SESSION_COMPAT") }
    var selected: Opportunity? = null
    var lastScanState: ScanState? = null
    var lastViopOpportunities: List<ViopOpportunity> = emptyList()
    var selectedViopOpportunity: ViopOpportunity? = null
    var selectedViopUnderlyingOpportunity: Opportunity? = null
    var selectedViopContract: ViopContract? = null
}
