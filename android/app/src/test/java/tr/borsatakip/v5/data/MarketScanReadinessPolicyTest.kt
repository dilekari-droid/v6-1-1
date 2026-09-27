package tr.borsatakip.v5.data

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketScanReadinessPolicyTest {
    @After
    fun resetRepository() = DynamicMarketScanResultRepository.resetForTests()

    @Test
    fun tradeWize_not_configured_does_not_block_bist() {
        val snapshot = snapshot(bistReady = true, viopReady = false, tradeWizeState = "NOT_CONFIGURED")
        val plan = MarketScanReadinessPolicy.plan("BIST", snapshot)

        assertFalse(snapshot.providerReady)
        assertTrue(plan.canStart)
        assertEquals(listOf("BIST"), plan.readyRoutes.map { it.id })
    }

    @Test
    fun all_with_bist_ready_and_viop_unavailable_runs_bist_and_returns_partial() = runTest {
        val snapshot = snapshot(bistReady = true, viopReady = false, tradeWizeState = "NOT_CONFIGURED")
        val called = mutableListOf<String>()
        val orchestrator = DynamicMarketScanOrchestrator { route, _, _, _ ->
            called += route.id
            Result.success(response(items = listOf(item("THYAO", "LONG"))))
        }

        val result = orchestrator.run(snapshot, "ALL", "5m")

        assertEquals(listOf("BIST"), called)
        assertEquals(DynamicMultiScanStatus.PARTIAL, result.status)
        assertEquals(listOf("THYAO"), result.items.map { it.symbol })
        assertTrue(result.skipped.any { it.route.id == "VIOP" })
    }

    @Test
    fun viop_connected_but_only_analysis_ready_stays_unavailable() {
        val base = snapshot(bistReady = true, viopReady = false, tradeWizeState = "CONNECTED")
        val viopFallbackOnly = capability(ready = false, count = 12).copy(
            supported = true,
            discovery = true,
            historicalData = true,
            analysisReady = true,
            scannerReady = false,
            reasonCode = "SCANNER_NOT_READY"
        )
        val snapshot = base.copy(markets = base.markets + ("VIOP" to viopFallbackOnly))

        val plan = MarketScanReadinessPolicy.plan("VIOP", snapshot)

        assertFalse(plan.canStart)
        assertEquals(listOf("VIOP"), plan.unavailableRoutes.map { it.route.id })
    }

    @Test
    fun viop_selection_when_unavailable_blocks_only_viop() = runTest {
        val snapshot = snapshot(bistReady = true, viopReady = false, tradeWizeState = "NOT_CONFIGURED")
        var calls = 0
        val orchestrator = DynamicMarketScanOrchestrator { _, _, _, _ ->
            calls++
            Result.success(response())
        }

        val result = orchestrator.run(snapshot, "VIOP", "5m")

        assertEquals(0, calls)
        assertEquals(DynamicMultiScanStatus.UNAVAILABLE, result.status)
        assertEquals(listOf("VIOP"), result.skipped.map { it.route.id })
    }

    @Test
    fun all_markets_unavailable_does_not_start_scan() = runTest {
        val snapshot = snapshot(bistReady = false, viopReady = false, tradeWizeState = "NOT_CONFIGURED")
        var calls = 0
        val orchestrator = DynamicMarketScanOrchestrator { _, _, _, _ ->
            calls++
            Result.success(response())
        }

        val result = orchestrator.run(snapshot, "ALL", "5m")

        assertEquals(0, calls)
        assertEquals(DynamicMultiScanStatus.UNAVAILABLE, result.status)
        assertTrue(result.completed.isEmpty())
    }

    @Test
    fun bist_selection_runs_even_when_viop_is_unavailable() = runTest {
        val snapshot = snapshot(bistReady = true, viopReady = false, tradeWizeState = "NOT_CONFIGURED")
        val called = mutableListOf<String>()
        val orchestrator = DynamicMarketScanOrchestrator { route, _, _, _ ->
            called += route.id
            Result.success(response(items = listOf(item("THYAO", "LONG"))))
        }

        val result = orchestrator.run(snapshot, "BIST", "5m")

        assertEquals(listOf("BIST"), called)
        assertEquals(DynamicMultiScanStatus.COMPLETED, result.status)
        assertEquals(listOf("THYAO"), result.items.map { it.symbol })
    }

    @Test
    fun all_ready_routes_complete_without_partial_status() = runTest {
        val base = snapshot(bistReady = true, viopReady = true, tradeWizeState = "CONNECTED")
        val readyMarkets = base.markets + mapOf(
            "COMMODITY" to capability(ready = true, count = 4),
            "FX" to capability(ready = true, count = 8),
            "INDEX" to capability(ready = true, count = 6)
        )
        val snapshot = base.copy(markets = readyMarkets)
        val called = mutableListOf<String>()
        val orchestrator = DynamicMarketScanOrchestrator { route, _, _, _ ->
            called += route.id
            Result.success(response(items = listOf(item(route.id, "WATCH").copy(market = route.backendMarket))))
        }

        val result = orchestrator.run(snapshot, "ALL", "5m")

        assertEquals(listOf("BIST", "VIOP", "COMMODITY", "FX", "INDEX"), called)
        assertEquals(DynamicMultiScanStatus.COMPLETED, result.status)
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun repository_clear_removes_previous_market_results() {
        val route = checkNotNull(MarketScanReadinessPolicy.routeForSelection("BIST"))
        DynamicMarketScanResultRepository.publish(
            DynamicMultiScanResult(
                status = DynamicMultiScanStatus.COMPLETED,
                selectionId = "BIST",
                completed = listOf(CompletedMarketScan(route, response(items = listOf(item("THYAO", "LONG")))))
            )
        )

        DynamicMarketScanResultRepository.clear()

        assertTrue(DynamicMarketScanResultRepository.filteredItems(DynamicResultFilter.ALL).isEmpty())
        assertTrue(DynamicMarketScanResultRepository.invalidFailures().isEmpty())
    }

    @Test
    fun long_short_watch_and_invalid_filters_use_same_repository_result() {
        val route = checkNotNull(MarketScanReadinessPolicy.routeForSelection("BIST"))
        val response = response(
            items = listOf(
                item("L", "LONG"),
                item("S", "SHORT"),
                item("W", "WATCH")
            ),
            failures = listOf(DynamicMarketScannerClient.Failure("X", "BIST", "NO_DATA", "Veri yok"))
        )
        DynamicMarketScanResultRepository.publish(
            DynamicMultiScanResult(
                status = DynamicMultiScanStatus.COMPLETED,
                selectionId = "BIST",
                completed = listOf(CompletedMarketScan(route, response))
            )
        )

        assertEquals(listOf("L", "S", "W"), DynamicMarketScanResultRepository.filteredItems(DynamicResultFilter.ALL).map { it.symbol })
        assertEquals(listOf("L"), DynamicMarketScanResultRepository.filteredItems(DynamicResultFilter.LONG).map { it.symbol })
        assertEquals(listOf("S"), DynamicMarketScanResultRepository.filteredItems(DynamicResultFilter.SHORT).map { it.symbol })
        assertEquals(listOf("W"), DynamicMarketScanResultRepository.filteredItems(DynamicResultFilter.WATCH).map { it.symbol })
        assertTrue(DynamicMarketScanResultRepository.filteredItems(DynamicResultFilter.INVALID).isEmpty())
        assertEquals(listOf("X"), DynamicMarketScanResultRepository.invalidFailures().map { it.symbol })
    }

    private fun snapshot(
        bistReady: Boolean,
        viopReady: Boolean,
        tradeWizeState: String
    ): MarketCapabilityClient.Snapshot {
        val markets = linkedMapOf(
            "BIST" to capability(ready = bistReady, count = if (bistReady) 630 else 0),
            "VIOP" to capability(ready = viopReady, count = if (viopReady) 20 else 0)
        )
        return MarketCapabilityClient.Snapshot(
            version = "6.1.1",
            providerReady = bistReady && viopReady,
            multiMarketReady = false,
            primaryProvider = "TradeWize",
            tradeWizeState = tradeWizeState,
            tradeWizeAdapterVerified = tradeWizeState == "CONNECTED",
            features = MarketCapabilityClient.FeatureCapability(false,false,false,false,false,false,false,false,false,false,false,false,false,false),
            batchPolicy = MarketCapabilityClient.BatchPolicy(20,175000,180000,185000),
            markets = markets
        )
    }

    private fun capability(ready: Boolean, count: Int): MarketCapabilityClient.MarketCapability =
        MarketCapabilityClient.MarketCapability(
            ready = ready,
            supported = ready,
            discovery = ready,
            marketData = ready,
            historicalData = ready,
            realtime = ready,
            realtimeReady = ready,
            analysisReady = ready,
            symbolCount = count,
            provider = null,
            reasonCode = if (ready) null else "PROVIDER_NOT_READY",
            message = null,
            contractsReady = ready,
            metadataReady = ready,
            quoteReady = ready,
            historyReady = ready,
            liquidityReady = ready,
            freshnessReady = ready,
            scannerReady = ready
        )

    private fun response(
        items: List<DynamicMarketScannerClient.Item> = emptyList(),
        failures: List<DynamicMarketScannerClient.Failure> = emptyList()
    ) = DynamicMarketScannerClient.Response(
        providerReady = false,
        globalProviderReady = false,
        multiMarketReady = false,
        realtimeReady = true,
        analysisReady = true,
        analysisMode = "REALTIME",
        timeframe = "5m",
        partial = failures.isNotEmpty(),
        engineVersion = "6.1.1",
        source = "TEST",
        scannedSymbols = items.size + failures.size,
        successfulCount = items.size,
        failedCount = failures.size,
        universeCount = items.size + failures.size,
        remainingSymbols = 0,
        nextOffset = items.size + failures.size,
        coverageComplete = true,
        items = items,
        failures = failures,
        batchSize = 60,
        concurrency = 2,
        pacingMs = 500,
        cacheTtlMs = 0L
    )

    private fun item(symbol: String, signal: String) = DynamicMarketScannerClient.Item(
        symbol = symbol,
        name = symbol,
        market = "BIST",
        assetType = "STOCK",
        signal = signal,
        technicalSignal = signal,
        verificationStatus = "VERIFIED",
        publicationMode = "LIVE",
        analysisTimeframe = "5m",
        score = 80,
        confidence = 90,
        confidenceBand = "HIGH",
        price = 100.0,
        dailyChangePct = 1.0,
        volume = 1000.0,
        rsi14 = 55.0,
        ema20 = 99.0,
        ema50 = 98.0,
        atrPct = 1.2,
        delaySeconds = 0,
        realtime = true,
        timestamp = 1_700_000_000_000L,
        source = "TEST"
    )
}
