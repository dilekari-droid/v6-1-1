package tr.borsatakip.v5.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DynamicMarketContractTest {
    @Test fun globalReadinessIsSeparateFromBistReadiness() {
        val j=JSONObject("""{"ok":true,"version":"2.0","providerReady":false,"multiMarketReady":false,"primaryConfiguredProvider":"TradeWize","tradeWize":{"state":"CONFIGURED","adapterVerified":true},"features":{"bistSnapshotBatch":true,"dynamicScanner":true,"realtimeScannerRest":false,"liveMarketWebSocket":false,"realtimeScannerWebSocket":false,"attestationReady":false},"markets":{"BIST":{"ready":true,"supported":true,"discovery":true,"marketData":true,"historicalData":true,"realtime":true,"analysisReady":true,"realtimeReady":true,"symbolCount":630},"VIOP":{"ready":false,"supported":true,"reasonCode":"STALE_DATA"}}}""")
        val x=MarketCapabilityClient.parse(j)
        assertFalse(x.providerReady)
        assertFalse(x.multiMarketReady)
        assertTrue(x.market("BIST")!!.ready)
        assertEquals(630,x.market("BIST")!!.symbolCount)
        assertTrue(x.market("BIST")!!.realtime)
        assertFalse(x.market("VIOP")!!.ready)
        assertTrue(x.features.bistSnapshotBatch)
        assertTrue(x.features.dynamicScanner)
        assertFalse(x.features.realtimeScannerRest)
        assertFalse(x.features.liveMarketWebSocket)
        assertFalse(x.features.realtimeScannerWebSocket)
        assertFalse(x.features.attestationReady)
    }

    @Test fun scanContractKeepsDelayedDataNonRealtime() {
        val j=JSONObject("""{"success":true,"providerReady":false,"globalProviderReady":false,"multiMarketReady":false,"realtimeReady":false,"analysisReady":true,"analysisMode":"DELAYED_ANALYSIS","timeframe":"5m","partial":false,"engineVersion":"V5.4.16","source":"TradeWize","scannedSymbols":1,"successfulCount":1,"failedCount":0,"universeCount":1,"remainingSymbols":0,"nextOffset":1,"coverageComplete":true,"scanPolicy":{"batchSize":60,"concurrency":2,"pacingMs":500,"cacheTtlMs":0},"items":[{"symbol":"THYAO","name":"THYAO","market":"BIST","assetType":"STOCK","signal":"WATCH","technicalSignal":"LONG","verificationStatus":"OBSERVATION","publicationMode":"OBSERVATION_ONLY","analysisTimeframe":"5m","score":72,"dataConfidence":75,"dataConfidenceBand":"DEGRADED","currentPrice":292.75,"realtime":false,"delaySeconds":903,"exchangeTimestamp":1790062647000,"source":"TradeWize/bars"}],"failures":[]}""")
        val x=DynamicMarketScannerClient.parse(j)
        assertEquals(1,x.scannedSymbols)
        assertEquals(60,x.batchSize)
        assertEquals(2,x.concurrency)
        assertFalse(x.items.single().realtime)
        assertEquals("OBSERVATION",x.items.single().verificationStatus)
        assertEquals("LONG",x.items.single().technicalSignal)
        assertEquals("5m",x.items.single().analysisTimeframe)
        assertEquals("5m",x.timeframe)
        assertTrue(x.analysisReady)
        assertFalse(x.realtimeReady)
        assertEquals(1,x.universeCount)
        assertEquals(0,x.remainingSymbols)
        assertTrue(x.coverageComplete)
    }

    @Test fun partialCoverageIsNeverParsedAsComplete() {
        val j=JSONObject("""{"success":true,"providerReady":true,"globalProviderReady":false,"multiMarketReady":false,"realtimeReady":true,"analysisReady":true,"analysisMode":"REALTIME","timeframe":"5m","partial":true,"engineVersion":"V5.4.16","source":"TradeWize","scannedSymbols":60,"successfulCount":60,"failedCount":0,"universeCount":125,"remainingSymbols":65,"nextOffset":60,"coverageComplete":false,"scanPolicy":{"batchSize":60,"concurrency":2,"pacingMs":500,"cacheTtlMs":0},"items":[],"failures":[]}""")
        val x=DynamicMarketScannerClient.parse(j)
        assertEquals(125,x.universeCount)
        assertEquals(65,x.remainingSymbols)
        assertEquals(60,x.nextOffset)
        assertFalse(x.coverageComplete)
        assertTrue(x.partial)
        assertFalse(x.globalProviderReady)
    }


    @Test fun customTimeframesAreRejectedByDynamicScannerContract() {
        assertTrue(DynamicMarketScannerClient.isDynamicTimeframeSupported("5m"))
        assertTrue(DynamicMarketScannerClient.isDynamicTimeframeSupported("1d"))
        assertFalse(DynamicMarketScannerClient.isDynamicTimeframeSupported("7m"))
        assertFalse(DynamicMarketScannerClient.isDynamicTimeframeSupported("20m"))
        assertFalse(DynamicMarketScannerClient.isDynamicTimeframeSupported("90m"))
    }

    @Test fun capabilityCarriesServerBatchPolicy() {
        val j=JSONObject("""{"ok":true,"version":"1.4.0","providerReady":false,"multiMarketReady":false,"primaryConfiguredProvider":"TradeWize","tradeWize":{"state":"CONFIGURED","adapterVerified":true},"features":{},"scanPolicy":{"snapshotBatchMaxSymbols":12,"snapshotBatchReadTimeoutMs":90000,"snapshotBatchCallTimeoutMs":95000,"snapshotBatchOuterTimeoutMs":100000},"markets":{}}""")
        val x=MarketCapabilityClient.parse(j)
        assertEquals(12, x.batchPolicy.maxSymbols)
        assertEquals(90_000L, x.batchPolicy.readTimeoutMs)
        assertEquals(95_000L, x.batchPolicy.callTimeoutMs)
        assertEquals(100_000L, x.batchPolicy.outerTimeoutMs)
    }

}
