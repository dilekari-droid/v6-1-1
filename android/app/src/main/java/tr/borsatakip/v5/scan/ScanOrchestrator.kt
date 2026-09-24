package tr.borsatakip.v5.scan

import android.content.Context
import tr.borsatakip.v5.analysis.ViopScanner
import tr.borsatakip.v5.data.BackendProvider
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.ViopStrategyDecisionService
import tr.borsatakip.v5.model.ViopScanProgress
import tr.borsatakip.v5.model.ViopScanResult

/** Canonical entry point for BIST/VIOP scan execution. UI and workers delegate here. */
class ScanOrchestrator(context: Context) {
    private val appContext = context.applicationContext

    suspend fun runViop(onProgress: (ViopScanProgress) -> Unit = {}): ViopScanResult {
        val backend = BackendProvider(appContext)
        return ViopScanner(
            backend,
            ProviderRouter(appContext),
            ViopStrategyDecisionService(appContext, backend)
        ).scan(onProgress)
    }

    suspend fun runBist(
        provider: tr.borsatakip.v5.data.MarketDataProvider,
        recorder: HistoryRecorder,
        mode: BistScanMode,
        onState: (ScanState) -> Unit = {}
    ): ScanState = BistScanner(provider, recorder, mode).scan(onState)
}
