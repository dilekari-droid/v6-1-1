package tr.borsatakip.v5.data

/**
 * Market scan readiness is derived per route. Aggregate provider flags such as
 * providerReady/multiMarketReady are informational and must never block an
 * independent market that is usable on its own.
 */
data class MarketScanRoute(
    val id: String,
    val label: String,
    val backendMarket: String,
    val assetType: String,
    val capabilityKey: String
)

enum class MarketScanReadinessState { READY, PARTIAL, STALE, UNAVAILABLE, ERROR }

data class MarketScanAvailability(
    val route: MarketScanRoute,
    val available: Boolean,
    val state: MarketScanReadinessState,
    val reasonCode: String? = null,
    val message: String? = null
) {
    val reason: String?
        get() = listOfNotNull(reasonCode?.takeIf { it.isNotBlank() }, message?.takeIf { it.isNotBlank() })
            .joinToString(" • ")
            .takeIf { it.isNotBlank() }
}

data class MarketScanPlan(
    val selectionId: String,
    val readyRoutes: List<MarketScanRoute>,
    val unavailableRoutes: List<MarketScanAvailability>,
    val routeStates: List<MarketScanAvailability>
) {
    val canStart: Boolean get() = readyRoutes.isNotEmpty()
    val isPartial: Boolean get() = readyRoutes.isNotEmpty() && unavailableRoutes.isNotEmpty()
}

object MarketScanReadinessPolicy {
    private val bist = MarketScanRoute("BIST", "BIST", "BIST", "STOCK", "BIST")
    private val viop = MarketScanRoute("VIOP", "VİOP", "VIOP", "FUTURE", "VIOP")
    private val commodity = MarketScanRoute("COMMODITY", "ALTIN/GÜMÜŞ/EMTİA", "COMMODITY", "COMMODITY", "COMMODITY")
    private val fx = MarketScanRoute("FX", "DÖVİZ", "FX", "FX", "FX")
    private val index = MarketScanRoute("INDEX", "ENDEKS", "INDEX", "INDEX", "INDEX")

    /** Canonical routes used by TÜMÜ. Shared COMMODITY capability is scanned once. */
    val allRoutes: List<MarketScanRoute> = listOf(bist, viop, commodity, fx, index)

    fun plan(selectionId: String, snapshot: MarketCapabilityClient.Snapshot): MarketScanPlan {
        val normalized = selectionId.trim().uppercase()
        val routes = if (normalized == "ALL") allRoutes else listOfNotNull(routeForSelection(normalized))
        val checks = routes.map { availability(it, snapshot) }
        return MarketScanPlan(
            selectionId = normalized,
            readyRoutes = checks.filter { it.available }.map { it.route },
            unavailableRoutes = checks.filterNot { it.available },
            routeStates = checks
        )
    }

    fun availability(route: MarketScanRoute, snapshot: MarketCapabilityClient.Snapshot): MarketScanAvailability {
        if (route.capabilityKey == "VIOP") {
            val tradeWize = snapshot.tradeWizeState.trim().uppercase()
            if (tradeWize != "CONNECTED") {
                val (code, message) = viopReason(tradeWize)
                return MarketScanAvailability(route, false, MarketScanReadinessState.UNAVAILABLE, code, message)
            }
        }

        val capability = snapshot.market(route.capabilityKey)
            ?: return MarketScanAvailability(
                route = route,
                available = false,
                state = MarketScanReadinessState.UNAVAILABLE,
                reasonCode = "CAPABILITY_MISSING",
                message = "Piyasa capability bilgisi backend tarafından yayınlanmadı."
            )

        // VIOP is fail-closed: CONNECTED is necessary but not sufficient. The
        // backend VIOP ready flag represents contracts/metadata/quote/history/
        // freshness/liquidity/scanner readiness. Historical fallback must not
        // be promoted to production VIOP scan readiness.
        if (route.capabilityKey == "VIOP") {
            if (capability.ready) {
                return MarketScanAvailability(route, true, MarketScanReadinessState.READY)
            }
            return unavailable(route, capability, "VIOP_SCAN_NOT_READY")
        }

        // Preserve the existing delayed-analysis behaviour for non-VIOP
        // markets. It is explicitly marked PARTIAL rather than being promoted
        // to realtime READY.
        if (capability.ready) {
            return MarketScanAvailability(route, true, MarketScanReadinessState.READY)
        }
        if (capability.analysisReady) {
            return MarketScanAvailability(
                route = route,
                available = true,
                state = MarketScanReadinessState.PARTIAL,
                reasonCode = capability.reasonCode ?: "DELAYED_ANALYSIS_ONLY",
                message = capability.message ?: "Canlı veri doğrulanmadı; yalnız doğrulanmış analiz verisi kullanılabilir."
            )
        }
        return unavailable(route, capability, "MARKET_SCAN_NOT_READY")
    }

    fun routeForSelection(selectionId: String): MarketScanRoute? = when (selectionId.trim().uppercase()) {
        "BIST" -> bist
        "VIOP" -> viop
        "GOLD" -> commodity.copy(id = "GOLD", label = "ALTIN")
        "SILVER" -> commodity.copy(id = "SILVER", label = "GÜMÜŞ")
        "COMMODITY" -> commodity
        "FX" -> fx
        "INDEX" -> index
        else -> null
    }

    private fun unavailable(
        route: MarketScanRoute,
        capability: MarketCapabilityClient.MarketCapability,
        fallbackCode: String
    ): MarketScanAvailability {
        val code = capability.reasonCode ?: fallbackCode
        val state = when {
            code.contains("STALE", ignoreCase = true) -> MarketScanReadinessState.STALE
            code.contains("ERROR", ignoreCase = true) || code.contains("FAILED", ignoreCase = true) -> MarketScanReadinessState.ERROR
            else -> MarketScanReadinessState.UNAVAILABLE
        }
        return MarketScanAvailability(
            route = route,
            available = false,
            state = state,
            reasonCode = code,
            message = capability.message
        )
    }

    private fun viopReason(state: String): Pair<String, String> = when (state) {
        "NOT_CONFIGURED" -> "TRADEWIZE_NOT_CONFIGURED" to "TradeWize bağlantısı bekleniyor."
        "CONFIGURED_UNVERIFIED" -> "TRADEWIZE_AUTH_PENDING" to "TradeWize kimlik doğrulaması henüz doğrulanmadı."
        "AUTH_FAILED" -> "TRADEWIZE_AUTH_FAILED" to "TradeWize kimlik doğrulaması başarısız."
        else -> "TRADEWIZE_NOT_CONNECTED" to "TradeWize bağlantısı üretim taraması için hazır değil."
    }
}
