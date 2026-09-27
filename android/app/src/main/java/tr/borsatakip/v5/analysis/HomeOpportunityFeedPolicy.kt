package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.data.DynamicMarketScanResultState
import tr.borsatakip.v5.data.DynamicMarketScannerClient
import tr.borsatakip.v5.data.DynamicMultiScanStatus
import tr.borsatakip.v5.data.ManualScanResultState
import tr.borsatakip.v5.data.RealTimeIntegrityPolicy
import tr.borsatakip.v5.model.Opportunity

/**
 * Canonical, fail-closed model consumed by Home -> Günün Fırsatları.
 * Home never promotes raw provider rows into a signal. Dynamic scanner rows
 * enter this model only after data + analysis validation and freshness checks.
 */
data class HomeOpportunityFeedItem(
    val symbol: String,
    val market: String,
    val price: Double,
    val timestamp: Long,
    val trendDirection: String,
    val trendStrength: Int,
    val signalStrength: Int,
    val finalStrength: Int,
    val signalType: String,
    val dataValidated: Boolean,
    val analysisValidated: Boolean,
    val providerStatus: String,
    val source: String,
    val dailyChangePct: Double? = null,
    val confidence: Int? = null,
    val riskScore: Int? = null,
    val sparkValues: List<Double> = emptyList(),
    val opportunity: Opportunity? = null
)

enum class HomeOpportunityFeedStatus { READY, PARTIAL, NO_SIGNAL, NO_DATA, ANALYSIS_UNAVAILABLE, STALE }

data class HomeOpportunityFeed(
    val status: HomeOpportunityFeedStatus,
    val items: List<HomeOpportunityFeedItem>,
    val source: String,
    val updatedAt: Long
)

object HomeOpportunityFeedPolicy {
    private val validVerification = setOf("VERIFIED", "VALID")
    private val readyProviderStates = setOf("READY", "CONNECTED", "REALTIME", "OK", "AVAILABLE")

    fun resolve(
        dynamic: DynamicMarketScanResultState,
        manual: ManualScanResultState,
        selected: HomeOpportunityFilterPolicy.Filter,
        nowMs: Long = System.currentTimeMillis()
    ): HomeOpportunityFeed {
        val dynamicFeed = dynamicFeed(dynamic, nowMs)
        val base = if (dynamicFeed != null) dynamicFeed else manualFeed(manual, nowMs)
        val filtered = when (selected) {
            HomeOpportunityFilterPolicy.Filter.ALL -> base.items
            HomeOpportunityFilterPolicy.Filter.LONG -> base.items.filter { it.trendDirection == "LONG" }
            HomeOpportunityFilterPolicy.Filter.SHORT -> base.items.filter { it.trendDirection == "SHORT" }
        }.sortedWith(
            compareByDescending<HomeOpportunityFeedItem> { it.finalStrength }
                .thenByDescending { it.signalStrength }
                .thenByDescending { it.trendStrength }
                .thenBy { it.symbol }
        )
        return base.copy(items = filtered)
    }

    private fun dynamicFeed(state: DynamicMarketScanResultState, nowMs: Long): HomeOpportunityFeed? {
        val result = state.result ?: return null
        val bistCompleted = result.completed.filter { it.route.backendMarket.equals("BIST", true) }
        if (bistCompleted.isEmpty()) return null

        val bistItems = bistCompleted.flatMap { it.response.items }
            .filter { it.market.equals("BIST", true) }
        val mapped = bistItems.mapNotNull { fromDynamic(it, nowMs) }
            .distinctBy { it.symbol.uppercase() }

        if (mapped.isNotEmpty()) {
            return HomeOpportunityFeed(
                status = if (result.status == DynamicMultiScanStatus.PARTIAL) HomeOpportunityFeedStatus.PARTIAL else HomeOpportunityFeedStatus.READY,
                items = mapped,
                source = "DYNAMIC_BIST_SCAN",
                updatedAt = state.updatedAt
            )
        }

        val dataValid = bistItems.any { dynamicDataValid(it, nowMs) }
        val analysisValid = bistItems.any { dynamicDataValid(it, nowMs) && dynamicAnalysisValid(it) }
        val anyStale = bistItems.any { item ->
            val ts = item.timestamp ?: 0L
            ts > 0L && nowMs - ts > RealTimeIntegrityPolicy.MAX_DATA_AGE_MS
        }
        val status = when {
            analysisValid -> HomeOpportunityFeedStatus.NO_SIGNAL
            dataValid -> HomeOpportunityFeedStatus.ANALYSIS_UNAVAILABLE
            anyStale -> HomeOpportunityFeedStatus.STALE
            else -> HomeOpportunityFeedStatus.NO_DATA
        }
        return HomeOpportunityFeed(status, emptyList(), "DYNAMIC_BIST_SCAN", state.updatedAt)
    }

    private fun manualFeed(state: ManualScanResultState, nowMs: Long): HomeOpportunityFeed {
        val mapped = state.items.mapNotNull { fromManual(it, nowMs) }
            .distinctBy { it.symbol.uppercase() }
        if (mapped.isNotEmpty()) {
            return HomeOpportunityFeed(HomeOpportunityFeedStatus.READY, mapped, state.source, state.updatedAt)
        }

        val unavailable = state.source in setOf(
            "HOME_REALTIME_NO_DATA", "HOME_REALTIME_DISCONNECTED", "HOME_REALTIME_ERROR",
            "REALTIME_SERVICE_NO_DATA", "REALTIME_SERVICE_DISCONNECTED", "REALTIME_SERVICE_ERROR",
            "NONE", "CLEARED"
        )
        val stale = state.items.any { it.exchangeTimestamp > 0L && nowMs - it.exchangeTimestamp > RealTimeIntegrityPolicy.MAX_DATA_AGE_MS }
        val hasRenderableAnalysis = state.items.any(HomeOpportunityFilterPolicy::hasRenderableAnalysis)
        val status = when {
            stale -> HomeOpportunityFeedStatus.STALE
            state.items.isNotEmpty() && hasRenderableAnalysis -> HomeOpportunityFeedStatus.NO_SIGNAL
            state.items.isNotEmpty() -> HomeOpportunityFeedStatus.ANALYSIS_UNAVAILABLE
            unavailable -> HomeOpportunityFeedStatus.NO_DATA
            else -> HomeOpportunityFeedStatus.NO_SIGNAL
        }
        return HomeOpportunityFeed(status, emptyList(), state.source, state.updatedAt)
    }

    private fun fromManual(item: Opportunity, nowMs: Long): HomeOpportunityFeedItem? {
        val marker = HomeRealtimeSignalPolicy.bistMarker(item, nowMs) ?: return null
        if (marker.direction !in setOf("LONG", "SHORT")) return null
        return HomeOpportunityFeedItem(
            symbol = item.symbol.trim().uppercase(),
            market = "BIST",
            price = item.price,
            timestamp = item.exchangeTimestamp,
            trendDirection = marker.direction,
            trendStrength = marker.strength,
            signalStrength = item.finalSignalScore.coerceIn(0, 100),
            finalStrength = maxOf(marker.strength, item.finalSignalScore.coerceIn(0, 100)),
            signalType = marker.direction,
            dataValidated = true,
            analysisValidated = true,
            providerStatus = "READY",
            source = item.source,
            dailyChangePct = item.dailyChangePct,
            confidence = item.dataConfidenceScore.coerceIn(0, 100),
            riskScore = item.riskScore.coerceIn(0, 100),
            sparkValues = item.candles.map { it.close },
            opportunity = item
        )
    }

    private fun fromDynamic(item: DynamicMarketScannerClient.Item, nowMs: Long): HomeOpportunityFeedItem? {
        if (!dynamicDataValid(item, nowMs) || !dynamicAnalysisValid(item)) return null
        val direction = item.trendDirection.trim().uppercase().takeIf { it in setOf("LONG", "SHORT") }
            ?: item.signal.trim().uppercase().takeIf { it in setOf("LONG", "SHORT") }
            ?: item.technicalSignal.trim().uppercase().takeIf { it in setOf("LONG", "SHORT") }
            ?: return null
        val trend = item.trendStrength.coerceIn(0, 100)
        val signal = item.signalStrength.coerceIn(0, 100)
        val final = item.finalStrength.coerceIn(0, 100)
        if (trend <= 0 || signal <= 0 || final <= 0) return null
        return HomeOpportunityFeedItem(
            symbol = item.symbol.trim().uppercase(),
            market = item.market.trim().uppercase(),
            price = item.price ?: return null,
            timestamp = item.timestamp ?: return null,
            trendDirection = direction,
            trendStrength = trend,
            signalStrength = signal,
            finalStrength = final,
            signalType = item.signalType.ifBlank { direction },
            dataValidated = true,
            analysisValidated = true,
            providerStatus = item.providerStatus,
            source = item.source,
            dailyChangePct = item.dailyChangePct,
            confidence = item.confidence.takeIf { it in 0..100 }
        )
    }

    private fun dynamicDataValid(item: DynamicMarketScannerClient.Item, nowMs: Long): Boolean {
        if (!item.market.equals("BIST", true)) return false
        if (!item.realtime || !item.dataValidated) return false
        if (item.price == null || !item.price.isFinite() || item.price <= 0.0) return false
        val ts = item.timestamp ?: return false
        if (ts <= 0L) return false
        val age = nowMs - ts
        if (age !in -RealTimeIntegrityPolicy.MAX_FUTURE_CLOCK_SKEW_MS..RealTimeIntegrityPolicy.MAX_DATA_AGE_MS) return false
        if (item.delaySeconds != null && item.delaySeconds !in 0..RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS) return false
        val provider = item.providerStatus.trim().uppercase()
        if (provider.isNotBlank() && provider !in readyProviderStates) return false
        return true
    }

    private fun dynamicAnalysisValid(item: DynamicMarketScannerClient.Item): Boolean {
        if (!item.analysisValidated) return false
        if (item.verificationStatus.trim().uppercase() !in validVerification) return false
        val direction = item.trendDirection.trim().uppercase().ifBlank {
            item.signal.trim().uppercase().ifBlank { item.technicalSignal.trim().uppercase() }
        }
        if (direction !in setOf("LONG", "SHORT", "WATCH", "NEUTRAL")) return false
        return true
    }
}
