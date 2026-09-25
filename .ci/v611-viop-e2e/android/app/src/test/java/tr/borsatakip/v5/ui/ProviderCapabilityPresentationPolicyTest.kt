package tr.borsatakip.v5.ui

import org.junit.Assert.*
import org.junit.Test
import tr.borsatakip.v5.data.MarketCapabilityClient

class ProviderCapabilityPresentationPolicyTest {
    private fun market(
        ready: Boolean = false,
        analysisReady: Boolean = false,
        count: Int = 0,
        reason: String? = null,
        message: String? = null,
        contractsReady: Boolean = ready,
        metadataReady: Boolean = ready,
        quoteReady: Boolean = ready,
        historyReady: Boolean = ready,
        liquidityReady: Boolean = ready,
        freshnessReady: Boolean = ready,
        scannerReady: Boolean = ready
    ) = MarketCapabilityClient.MarketCapability(
        ready = ready,
        supported = ready || analysisReady,
        discovery = ready || analysisReady,
        marketData = ready,
        historicalData = ready || analysisReady,
        realtime = ready,
        realtimeReady = ready,
        analysisReady = analysisReady,
        symbolCount = count,
        provider = null,
        reasonCode = reason,
        message = message,
        contractsReady = contractsReady,
        metadataReady = metadataReady,
        quoteReady = quoteReady,
        historyReady = historyReady,
        liquidityReady = liquidityReady,
        freshnessReady = freshnessReady,
        scannerReady = scannerReady
    )

    private fun snapshot(
        providerReady: Boolean = false,
        tradeWizeState: String = "NOT_CONFIGURED",
        bist: MarketCapabilityClient.MarketCapability = market(analysisReady = true, count = 630),
        viop: MarketCapabilityClient.MarketCapability = market(reason = "TRADEWIZE_NOT_CONFIGURED")
    ) = MarketCapabilityClient.Snapshot(
        version = "6.1.1",
        providerReady = providerReady,
        multiMarketReady = false,
        primaryProvider = "YahooFallback",
        tradeWizeState = tradeWizeState,
        tradeWizeAdapterVerified = false,
        features = MarketCapabilityClient.FeatureCapability(false,false,false,false,false,false,false,false,false,false,false,false,false,false),
        batchPolicy = MarketCapabilityClient.BatchPolicy(20,175000,180000,185000),
        markets = mapOf("BIST" to bist, "VIOP" to viop)
    )

    @Test fun global_status_separates_bist_fallback_from_viop_unavailable() {
        val text = ProviderCapabilityPresentationPolicy.globalStatus(snapshot())
        assertTrue(text.contains("GLOBAL PROVIDER HAZIR DEĞİL"))
        assertTrue(text.contains("TradeWize: NOT_CONFIGURED"))
        assertTrue(text.contains("BIST: FALLBACK / GECİKMELİ • 630 sembol"))
        assertTrue(text.contains("VİOP: KULLANILAMIYOR • TradeWize bağlantısı bekleniyor"))
    }

    @Test fun not_configured_tradewize_overrides_viop_analysis_ready_for_ui_availability() {
        val s = snapshot(viop = market(analysisReady = true, count = 12))
        assertFalse(ProviderCapabilityPresentationPolicy.isMarketAvailable("VIOP", s))
        assertTrue(ProviderCapabilityPresentationPolicy.marketSummary("VİOP", "VIOP", s).contains("KULLANILAMIYOR"))
    }

    @Test fun bist_fallback_remains_available() {
        assertTrue(ProviderCapabilityPresentationPolicy.isMarketAvailable("BIST", snapshot()))
    }

    @Test fun configured_viop_reports_first_failed_stage() {
        val s = snapshot(
            tradeWizeState = "CONNECTED",
            viop = market(
                analysisReady = false, count = 18,
                contractsReady = true, metadataReady = true,
                quoteReady = true, historyReady = false
            )
        )
        assertTrue(ProviderCapabilityPresentationPolicy.viopStatus(s).contains("HISTORY_INVALID"))
    }

    @Test fun configured_viop_uses_backend_capability_without_fabricating_readiness() {
        val s = snapshot(
            tradeWizeState = "CONNECTED",
            viop = market(ready = true, analysisReady = true, count = 18)
        )
        assertTrue(ProviderCapabilityPresentationPolicy.isMarketAvailable("VIOP", s))
        assertTrue(ProviderCapabilityPresentationPolicy.viopStatus(s).contains("REALTIME HAZIR"))
    }
}
