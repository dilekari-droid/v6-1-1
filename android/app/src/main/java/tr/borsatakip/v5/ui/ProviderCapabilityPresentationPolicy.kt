package tr.borsatakip.v5.ui

import tr.borsatakip.v5.data.MarketCapabilityClient
import tr.borsatakip.v5.data.MarketScanReadinessPolicy

/**
 * Presents provider state and market scan readiness as separate concepts.
 * Aggregate provider flags are informational only and never gate BIST.
 */
object ProviderCapabilityPresentationPolicy {
    private const val TRADEWIZE_NOT_CONFIGURED = "NOT_CONFIGURED"

    fun globalStatus(snapshot: MarketCapabilityClient.Snapshot): String {
        val plan = MarketScanReadinessPolicy.plan("ALL", snapshot)
        val headline = when {
            plan.readyRoutes.isEmpty() -> "! PİYASA TARAMA HAZIR DEĞİL"
            plan.unavailableRoutes.isEmpty() -> "✓ TÜM PİYASALAR TARAMAYA HAZIR"
            else -> "KISMİ TARAMA HAZIR • ${plan.readyRoutes.joinToString("/") { it.label }}"
        }
        val tradeWize = "TradeWize: ${snapshot.tradeWizeState.ifBlank { "UNKNOWN" }}"
        return listOf(headline, tradeWize, bistStatus(snapshot), viopStatus(snapshot)).joinToString("\n")
    }

    fun bistStatus(snapshot: MarketCapabilityClient.Snapshot): String {
        val bist = snapshot.market("BIST")
        val count = bist?.symbolCount?.takeIf { it > 0 }?.let { " • $it sembol" }.orEmpty()
        return when {
            bist?.ready == true -> "BIST: REALTIME HAZIR$count"
            bist?.analysisReady == true -> "BIST: FALLBACK / GECİKMELİ$count"
            else -> "BIST: KULLANILAMIYOR${bist?.reasonCode?.let { " • $it" }.orEmpty()}"
        }
    }

    fun viopStatus(snapshot: MarketCapabilityClient.Snapshot): String {
        val viop = snapshot.market("VIOP")
        val count = viop?.symbolCount?.takeIf { it > 0 }?.let { " • $it kontrat" }.orEmpty()
        if (isTradeWizeNotConfigured(snapshot)) return "VİOP: KULLANILAMIYOR • TradeWize bağlantısı bekleniyor"
        if (snapshot.tradeWizeState.equals("AUTH_FAILED", ignoreCase = true)) return "VİOP: KULLANILAMIYOR • TRADEWIZE_AUTH_FAILED"
        if (snapshot.tradeWizeState.equals("CONFIGURED_UNVERIFIED", ignoreCase = true)) return "VİOP: KULLANILAMIYOR • TRADEWIZE_AUTH_BEKLENİYOR"
        return when {
            viop?.ready == true -> "VİOP: REALTIME HAZIR$count"
            viop == null -> "VİOP: KULLANILAMIYOR • CAPABILITY_YOK"
            !viop.contractsReady -> "VİOP: KULLANILAMIYOR • CONTRACTS_MISSING"
            !viop.metadataReady -> "VİOP: KULLANILAMIYOR • METADATA_INVALID"
            !viop.quoteReady -> "VİOP: KULLANILAMIYOR • QUOTE_INVALID"
            !viop.historyReady -> "VİOP: KULLANILAMIYOR • HISTORY_INVALID"
            !viop.liquidityReady -> "VİOP: KULLANILAMIYOR • LIQUIDITY_INVALID"
            !viop.freshnessReady -> "VİOP: KULLANILAMIYOR • FRESHNESS_INVALID"
            !viop.scannerReady -> "VİOP: KULLANILAMIYOR • SCANNER_NOT_READY"
            viop.analysisReady -> "VİOP: GECİKMELİ ANALİZ HAZIR$count"
            else -> "VİOP: KULLANILAMIYOR${viop.reasonCode?.let { " • $it" }.orEmpty()}"
        }
    }

    fun marketSummary(label: String, capabilityKey: String, snapshot: MarketCapabilityClient.Snapshot): String {
        val selectionId = capabilityKey.trim().uppercase()
        val plan = MarketScanReadinessPolicy.plan(selectionId, snapshot)
        val check = plan.routeStates.firstOrNull()
        if (check != null && !check.available) {
            val detail = check.reason ?: "MARKET_SCAN_NOT_READY"
            return "$label KULLANILAMIYOR • $detail"
        }
        val capability = snapshot.market(capabilityKey)
        return when {
            capability?.ready == true -> "$label REALTIME HAZIR • ${assetCount(capability.symbolCount, capabilityKey)}${capability.provider ?: snapshot.primaryProvider}"
            capability?.analysisReady == true -> "$label KISMİ / GECİKMELİ ANALİZ • ${assetCount(capability.symbolCount, capabilityKey)}${capability.message ?: "Canlı quote doğrulanmadı; doğrulanmış analiz verisi kullanılabilir."}"
            else -> "$label HAZIR DEĞİL • ${capability?.reasonCode ?: "PROVIDER_NOT_READY"} • ${capability?.message ?: "Dinamik provider desteği yok."}"
        }
    }

    fun isMarketAvailable(capabilityKey: String, snapshot: MarketCapabilityClient.Snapshot): Boolean =
        MarketScanReadinessPolicy.plan(capabilityKey, snapshot).canStart

    private fun isTradeWizeNotConfigured(snapshot: MarketCapabilityClient.Snapshot): Boolean =
        snapshot.tradeWizeState.trim().equals(TRADEWIZE_NOT_CONFIGURED, ignoreCase = true)

    private fun assetCount(count: Int, capabilityKey: String): String = count.takeIf { it > 0 }?.let {
        when {
            capabilityKey.equals("VIOP", ignoreCase = true) -> "$it kontrat • "
            capabilityKey.equals("BIST", ignoreCase = true) -> "$it sembol • "
            else -> "$it varlık • "
        }
    }.orEmpty()
}
