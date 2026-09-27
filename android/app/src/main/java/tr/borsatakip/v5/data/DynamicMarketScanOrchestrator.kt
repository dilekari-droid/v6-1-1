package tr.borsatakip.v5.data

enum class DynamicMultiScanStatus { COMPLETED, PARTIAL, UNAVAILABLE, ERROR }

data class CompletedMarketScan(
    val route: MarketScanRoute,
    val response: DynamicMarketScannerClient.Response
)

data class FailedMarketScan(
    val route: MarketScanRoute,
    val message: String
)

data class DynamicMultiScanResult(
    val status: DynamicMultiScanStatus,
    val selectionId: String,
    val completed: List<CompletedMarketScan> = emptyList(),
    val skipped: List<MarketScanAvailability> = emptyList(),
    val failed: List<FailedMarketScan> = emptyList()
) {
    val items: List<DynamicMarketScannerClient.Item>
        get() = completed.flatMap { it.response.items }
            .distinctBy { "${it.market.uppercase()}:${it.symbol.uppercase()}" }

    val failures: List<DynamicMarketScannerClient.Failure>
        get() = completed.flatMap { it.response.failures } + failed.map {
            DynamicMarketScannerClient.Failure(
                symbol = null,
                market = it.route.backendMarket,
                code = "MARKET_SCAN_ERROR",
                message = it.message
            )
        }

    val scannedSymbols: Int get() = completed.sumOf { it.response.scannedSymbols }
    val universeCount: Int get() = completed.sumOf { it.response.universeCount }
    val successfulCount: Int get() = completed.sumOf { it.response.successfulCount }
    val failedCount: Int get() = completed.sumOf { it.response.failedCount } + failed.size

    val isPartial: Boolean
        get() = status == DynamicMultiScanStatus.PARTIAL
}

/**
 * Runs each READY market independently. One unavailable provider route never
 * prevents another market from being scanned.
 */
class DynamicMarketScanOrchestrator(
    private val loadMarket: suspend (
        route: MarketScanRoute,
        timeframe: String,
        minScore: Int,
        includeWatch: Boolean
    ) -> Result<DynamicMarketScannerClient.Response>
) {
    suspend fun run(
        snapshot: MarketCapabilityClient.Snapshot,
        selectionId: String,
        timeframe: String,
        minScore: Int = 0,
        includeWatch: Boolean = true
    ): DynamicMultiScanResult {
        val plan = MarketScanReadinessPolicy.plan(selectionId, snapshot)
        if (!plan.canStart) {
            return DynamicMultiScanResult(
                status = DynamicMultiScanStatus.UNAVAILABLE,
                selectionId = plan.selectionId,
                skipped = plan.unavailableRoutes
            )
        }

        val completed = mutableListOf<CompletedMarketScan>()
        val failed = mutableListOf<FailedMarketScan>()
        for (route in plan.readyRoutes) {
            loadMarket(route, timeframe, minScore, includeWatch).fold(
                onSuccess = { completed += CompletedMarketScan(route, it) },
                onFailure = { failed += FailedMarketScan(route, it.message ?: it.javaClass.simpleName) }
            )
        }

        val responsePartial = completed.any { it.response.partial || it.response.failedCount > 0 || it.response.failures.isNotEmpty() }
        val status = when {
            completed.isEmpty() && failed.isNotEmpty() -> DynamicMultiScanStatus.ERROR
            completed.isNotEmpty() && (plan.unavailableRoutes.isNotEmpty() || failed.isNotEmpty() || responsePartial) -> DynamicMultiScanStatus.PARTIAL
            completed.isNotEmpty() -> DynamicMultiScanStatus.COMPLETED
            else -> DynamicMultiScanStatus.UNAVAILABLE
        }
        return DynamicMultiScanResult(
            status = status,
            selectionId = plan.selectionId,
            completed = completed,
            skipped = plan.unavailableRoutes,
            failed = failed
        )
    }
}
