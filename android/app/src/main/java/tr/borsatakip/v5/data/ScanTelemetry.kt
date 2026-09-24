package tr.borsatakip.v5.data

import android.util.Log

/** Standardized scan timing profile shared by foreground/background scan paths. */
data class ScanProfile(
    val runId: String,
    val startedAt: Long,
    val completedAt: Long,
    val stagesMs: Map<String, Long>,
    val processed: Int,
    val successful: Int,
    val failed: Int,
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0
)

object ScanTelemetry {
    fun record(profile: ScanProfile) {
        val stages = profile.stagesMs.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}ms" }
        Log.i("ScanTelemetry", "run=${profile.runId} total=${profile.completedAt - profile.startedAt}ms processed=${profile.processed} successful=${profile.successful} failed=${profile.failed} cacheHit=${profile.cacheHits} cacheMiss=${profile.cacheMisses} stages=$stages")
    }
}
