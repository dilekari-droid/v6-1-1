package tr.borsatakip.v5.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class DynamicMarketScanResultRepositoryTest {
    @After fun cleanup() = DynamicMarketScanResultRepository.resetForTests()

    @Test fun shortFilterUsesCanonicalTrendDirectionNotRealtimeWatchSignal() {
        val route = checkNotNull(MarketScanReadinessPolicy.routeForSelection("BIST"))
        val item = DynamicMarketScannerClient.Item(
            symbol="THYAO", name="THYAO", market="BIST", assetType="STOCK",
            signal="WATCH", technicalSignal="SHORT", verificationStatus="VERIFIED",
            publicationMode="LIVE", analysisTimeframe="5m", score=91, confidence=90,
            confidenceBand="HIGH", price=100.0, dailyChangePct=-1.0, volume=1000.0,
            rsi14=45.0, ema20=99.0, ema50=101.0, atrPct=1.0, delaySeconds=0,
            realtime=true, timestamp=1_800_000_000_000L, source="TEST",
            trendDirection="SHORT", trendStrength=91, signalStrength=91, finalStrength=91,
            signalType="SHORT", dataValidated=true, analysisValidated=true, providerStatus="READY"
        )
        val response = DynamicMarketScannerClient.Response(
            providerReady=true, globalProviderReady=false, multiMarketReady=false,
            realtimeReady=true, analysisReady=true, analysisMode="REALTIME", timeframe="5m",
            partial=false, engineVersion="6.1.1", source="TEST", scannedSymbols=1,
            successfulCount=1, failedCount=0, universeCount=1, remainingSymbols=0,
            nextOffset=1, coverageComplete=true, items=listOf(item), failures=emptyList(),
            batchSize=60, concurrency=2, pacingMs=500, cacheTtlMs=0L
        )
        DynamicMarketScanResultRepository.publish(
            DynamicMultiScanResult(
                status=DynamicMultiScanStatus.COMPLETED,
                selectionId="BIST",
                completed=listOf(CompletedMarketScan(route,response))
            )
        )
        assertEquals("SHORT", DynamicMarketScanResultRepository.canonicalDirection(item))
        assertEquals(listOf("THYAO"), DynamicMarketScanResultRepository.filteredItems(DynamicResultFilter.SHORT).map { it.symbol })
        assertEquals(emptyList<String>(), DynamicMarketScanResultRepository.filteredItems(DynamicResultFilter.LONG).map { it.symbol })
    }
}
