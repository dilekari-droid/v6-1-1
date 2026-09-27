package tr.borsatakip.v5.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.data.CompletedMarketScan
import tr.borsatakip.v5.data.DynamicMarketScanResultState
import tr.borsatakip.v5.data.DynamicMarketScannerClient
import tr.borsatakip.v5.data.DynamicMultiScanResult
import tr.borsatakip.v5.data.DynamicMultiScanStatus
import tr.borsatakip.v5.data.ManualScanResultState
import tr.borsatakip.v5.data.MarketScanReadinessPolicy
import tr.borsatakip.v5.data.RealTimeIntegrityPolicy

class HomeOpportunityFeedPolicyTest {
    private val now = 1_800_000_000_000L

    @Test
    fun dynamic_bist_scan_feeds_home_all_with_only_verified_long_short_sorted_by_strength() {
        val state = dynamicState(
            listOf(
                item("LONG1", "LONG", 82),
                item("WATCH1", "WATCH", 99),
                item("SHORT1", "SHORT", 94),
                item("BAD", "LONG", 98).copy(dataValidated = false)
            )
        )

        val feed = HomeOpportunityFeedPolicy.resolve(state, ManualScanResultState(), HomeOpportunityFilterPolicy.Filter.ALL, now)

        assertEquals(HomeOpportunityFeedStatus.READY, feed.status)
        assertEquals(listOf("SHORT1", "LONG1"), feed.items.map { it.symbol })
        assertTrue(feed.items.all { it.dataValidated && it.analysisValidated })
    }

    @Test
    fun short_tab_never_contains_long() {
        val state = dynamicState(listOf(item("L", "LONG", 95), item("S", "SHORT", 80)))

        val feed = HomeOpportunityFeedPolicy.resolve(state, ManualScanResultState(), HomeOpportunityFilterPolicy.Filter.SHORT, now)

        assertEquals(listOf("S"), feed.items.map { it.symbol })
        assertTrue(feed.items.all { it.trendDirection == "SHORT" })
    }

    @Test
    fun stale_dynamic_result_is_not_rendered_as_live_opportunity() {
        val stale = item("OLD", "LONG", 90).copy(timestamp = now - RealTimeIntegrityPolicy.MAX_DATA_AGE_MS - 1)
        val feed = HomeOpportunityFeedPolicy.resolve(dynamicState(listOf(stale)), ManualScanResultState(), HomeOpportunityFilterPolicy.Filter.ALL, now)

        assertTrue(feed.items.isEmpty())
        assertEquals(HomeOpportunityFeedStatus.STALE, feed.status)
    }

    @Test
    fun completed_analysis_with_watch_only_reports_no_signal_not_no_data() {
        val feed = HomeOpportunityFeedPolicy.resolve(
            dynamicState(listOf(item("W", "WATCH", 70))),
            ManualScanResultState(),
            HomeOpportunityFilterPolicy.Filter.ALL,
            now
        )

        assertTrue(feed.items.isEmpty())
        assertEquals(HomeOpportunityFeedStatus.NO_SIGNAL, feed.status)
    }

    @Test
    fun dynamic_scan_is_not_erased_by_unrelated_realtime_disconnect_state() {
        val dynamic = dynamicState(listOf(item("THYAO", "LONG", 88)))
        val manual = ManualScanResultState(items = emptyList(), source = "HOME_REALTIME_DISCONNECTED", updatedAt = now + 5_000)

        val feed = HomeOpportunityFeedPolicy.resolve(dynamic, manual, HomeOpportunityFilterPolicy.Filter.ALL, now)

        assertEquals(listOf("THYAO"), feed.items.map { it.symbol })
        assertEquals("DYNAMIC_BIST_SCAN", feed.source)
    }

    @Test
    fun progress_percent_is_derived_from_real_processed_over_universe() {
        val progress = DynamicMarketScannerClient.ScanProgress(315, 630, 312, 3)
        assertEquals(50, progress.percent)
        assertEquals(0, DynamicMarketScannerClient.ScanProgress(0, 0, 0, 0).percent)
        assertEquals(100, DynamicMarketScannerClient.ScanProgress(630, 630, 627, 3).percent)
    }

    private fun dynamicState(items: List<DynamicMarketScannerClient.Item>): DynamicMarketScanResultState {
        val route = checkNotNull(MarketScanReadinessPolicy.routeForSelection("BIST"))
        val response = DynamicMarketScannerClient.Response(
            providerReady = true,
            globalProviderReady = false,
            multiMarketReady = false,
            realtimeReady = true,
            analysisReady = true,
            analysisMode = "REALTIME",
            timeframe = "5m",
            partial = false,
            engineVersion = "6.1.1",
            source = "TEST",
            scannedSymbols = items.size,
            successfulCount = items.size,
            failedCount = 0,
            universeCount = items.size,
            remainingSymbols = 0,
            nextOffset = items.size,
            coverageComplete = true,
            items = items,
            failures = emptyList(),
            batchSize = 60,
            concurrency = 2,
            pacingMs = 500,
            cacheTtlMs = 0L
        )
        return DynamicMarketScanResultState(
            result = DynamicMultiScanResult(
                status = DynamicMultiScanStatus.COMPLETED,
                selectionId = "BIST",
                completed = listOf(CompletedMarketScan(route, response))
            ),
            updatedAt = now
        )
    }

    private fun item(symbol: String, direction: String, strength: Int) = DynamicMarketScannerClient.Item(
        symbol = symbol,
        name = symbol,
        market = "BIST",
        assetType = "STOCK",
        signal = direction,
        technicalSignal = direction,
        verificationStatus = "VERIFIED",
        publicationMode = "LIVE",
        analysisTimeframe = "5m",
        score = strength,
        confidence = 90,
        confidenceBand = "HIGH",
        price = 100.0,
        dailyChangePct = 1.0,
        volume = 10_000.0,
        rsi14 = 55.0,
        ema20 = 99.0,
        ema50 = 98.0,
        atrPct = 1.2,
        delaySeconds = 0,
        realtime = true,
        timestamp = now - 1_000,
        source = "TEST",
        trendDirection = direction,
        trendStrength = strength,
        signalStrength = strength,
        finalStrength = strength,
        signalType = direction,
        dataValidated = true,
        analysisValidated = true,
        providerStatus = "READY"
    )
}
