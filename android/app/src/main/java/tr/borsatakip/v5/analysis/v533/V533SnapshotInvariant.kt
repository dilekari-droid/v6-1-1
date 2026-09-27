package tr.borsatakip.v5.analysis.v533

import kotlin.math.abs
import tr.borsatakip.v5.analysis.v531.ScoringConfig

data class V533SnapshotMetadata(
    val provider: String,
    val providerId: String,
    val providerVersion: String,
    val sourceType: String,
    val snapshotId: String,
    val requestId: String,
    val generatedAt: Long,
    val serverTime: Long,
    val trackedSymbols: Int,
    val freshSymbols: Int,
    val readySymbols: Int,
    /** Number of opportunity items actually returned in this snapshot. It is distinct from readySymbols. */
    val resultItemCount: Int,
    val universeVerified: Boolean,
    val universeSymbols: Int,
    val freshCoveragePct: Double?,
    val readyCoveragePct: Double?,
    val minReadyBars: Int,
    val engineVersion: Long,
    val calculationEngineVersion: String,
    val engineMode: String,
    val analysisTimeframeMinutes: Int,
    val scanCadenceMinutes: Int,
    val scanMode: String,
    val snapshotHash: String
)

data class V533SnapshotItemRef(val symbol: String, val calculationInputHash: String, val calculationOutputHash: String)
data class V533SnapshotInvariantReport(val accepted: Boolean, val violations: List<String>)

object V533SnapshotInvariant {
    private const val COVERAGE_TOLERANCE_PCT = 0.15

    fun evaluate(metadata: V533SnapshotMetadata, itemRefs: List<V533SnapshotItemRef>): V533SnapshotInvariantReport {
        val v = mutableListOf<String>()
        if (metadata.provider.isBlank()) v += "PROVIDER_REQUIRED"
        if (metadata.providerId.isBlank()) v += "PROVIDER_ID_REQUIRED"
        if (metadata.providerVersion.isBlank()) v += "PROVIDER_VERSION_REQUIRED"
        if (metadata.sourceType.isBlank()) v += "SOURCE_TYPE_REQUIRED"
        if (metadata.snapshotId.isBlank()) v += "SNAPSHOT_ID_REQUIRED"
        if (metadata.requestId.isBlank()) v += "REQUEST_ID_REQUIRED"
        if (metadata.generatedAt <= 0L) v += "GENERATED_AT"
        if (metadata.serverTime <= 0L) v += "SERVER_TIME"
        if (metadata.generatedAt > metadata.serverTime + 15_000L) v += "GENERATED_AFTER_SERVER"
        if (metadata.trackedSymbols < 0) v += "TRACKED_NEGATIVE"
        if (metadata.freshSymbols !in 0..metadata.trackedSymbols.coerceAtLeast(0)) v += "FRESH_COUNT"
        if (metadata.readySymbols !in 0..metadata.freshSymbols.coerceAtLeast(0)) v += "READY_COUNT"
        if (metadata.resultItemCount < 0) v += "RESULT_ITEM_COUNT_NEGATIVE"
        if (metadata.resultItemCount > metadata.readySymbols.coerceAtLeast(0)) v += "RESULT_ITEM_COUNT_GT_READY"
        if (itemRefs.size != metadata.resultItemCount) v += "RESULT_ITEM_COUNT_MISMATCH"
        if (metadata.universeVerified && metadata.universeSymbols <= 0) v += "VERIFIED_UNIVERSE_EMPTY"
        if (!metadata.universeVerified && metadata.universeSymbols != 0) v += "UNVERIFIED_UNIVERSE_NONZERO"
        if (metadata.universeVerified && metadata.trackedSymbols > metadata.universeSymbols) v += "TRACKED_GT_UNIVERSE"
        if (metadata.calculationEngineVersion != ScoringConfig.ENGINE_VERSION) v += "ENGINE_VERSION"
        if (metadata.engineMode.trim().uppercase() != "REMOTE") v += "ENGINE_MODE"
        if (metadata.analysisTimeframeMinutes !in 1..239) v += "TIMEFRAME_RANGE"
        if (metadata.scanCadenceMinutes !in 1..1440) v += "CADENCE_RANGE"
        if (metadata.scanMode.isBlank()) v += "SCAN_MODE_REQUIRED"
        if (metadata.minReadyBars <= 0) v += "MIN_READY_BARS"
        if (metadata.engineVersion <= 0L) v += "ENGINE_BUILD_VERSION_INVALID"

        fun expectedPct(count: Int): Double = if (metadata.trackedSymbols == 0) 0.0 else count * 100.0 / metadata.trackedSymbols
        fun coverage(name: String, actual: Double?, expected: Double) {
            if (actual == null || !actual.isFinite() || actual !in 0.0..100.0) v += "${name}_RANGE"
            else if (abs(actual - expected) > COVERAGE_TOLERANCE_PCT) v += "${name}_MISMATCH"
        }
        coverage("FRESH_COVERAGE", metadata.freshCoveragePct, expectedPct(metadata.freshSymbols))
        coverage("READY_COVERAGE", metadata.readyCoveragePct, expectedPct(metadata.readySymbols))

        val symbols = itemRefs.map { it.symbol.trim().uppercase() }
        if (symbols.any { it.isBlank() }) v += "ITEM_SYMBOL_REQUIRED"
        if (symbols.size != symbols.distinct().size) v += "DUPLICATE_ITEM_SYMBOL"
        if (itemRefs.any { !V533AuditHash.isSha256Hex(it.calculationInputHash) }) v += "ITEM_INPUT_HASH_FORMAT"
        if (itemRefs.any { !V533AuditHash.isSha256Hex(it.calculationOutputHash) }) v += "ITEM_OUTPUT_HASH_FORMAT"
        if (!V533AuditHash.isSha256Hex(metadata.snapshotHash)) v += "SNAPSHOT_HASH_FORMAT"
        else if (itemRefs.all { V533AuditHash.isSha256Hex(it.calculationInputHash) && V533AuditHash.isSha256Hex(it.calculationOutputHash) }) {
            if (!metadata.snapshotHash.equals(V533AuditHash.snapshotHash(metadata, itemRefs), ignoreCase = true)) v += "SNAPSHOT_HASH_MISMATCH"
        }
        return V533SnapshotInvariantReport(v.isEmpty(), v.distinct())
    }
}
