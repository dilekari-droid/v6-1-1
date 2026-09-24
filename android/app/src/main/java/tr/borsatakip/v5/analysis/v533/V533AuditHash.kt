package tr.borsatakip.v5.analysis.v533

import java.security.MessageDigest
import java.util.Locale
import tr.borsatakip.v5.analysis.v531.RemoteEngineMetadata

object V533AuditHash {
    private val hex64 = Regex("^[0-9a-fA-F]{64}$")

    fun isSha256Hex(value: String): Boolean = hex64.matches(value.trim())

    fun sha256Hex(canonical: String): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.US, it) }

    fun calculationOutputHash(metadata: RemoteEngineMetadata): String = sha256Hex(buildString {
        append("inputHash=").append(metadata.calculationInputHash.trim().lowercase()).append('\n')
        append("engine=").append(metadata.calculationEngineVersion.trim()).append('\n')
        append("mode=").append(metadata.engineMode.trim().uppercase()).append('\n')
        append("long=").append(metadata.longScore).append('\n')
        append("short=").append(metadata.shortScore).append('\n')
        append("final=").append(metadata.finalSignalScore).append('\n')
        append("confidence=").append(metadata.dataConfidenceScore).append('\n')
        append("risk=").append(metadata.riskScore).append('\n')
        append("penalty=").append(metadata.conflictPenalty).append('\n')
        append("coverage=").append(metadata.signalAvailableWeight).append('\n')
        append("mtf=").append(metadata.mtfConsensusScore?.toString() ?: "NA").append('\n')
        append("mtfAvailableCount=").append(metadata.mtfAvailableCount).append('\n')
        append("mtfExpectedCount=").append(metadata.mtfExpectedCount).append('\n')
        append("dominant=").append(metadata.dominantDirection.trim().uppercase()).append('\n')
        append("direction=").append(metadata.direction.trim().uppercase()).append('\n')
        append("decision=").append(metadata.decisionState.trim().uppercase()).append('\n')
        append("validity=").append(metadata.signalValidity.trim().uppercase()).append('\n')
        append("outputSchema=").append(metadata.calculationOutputSchema.trim()).append('\n')
        append("qualityClass=").append(metadata.qualityClass.trim().uppercase()).append('\n')
        append("timingStatus=").append(metadata.timingStatus.trim().uppercase()).append('\n')
        append("marketRegime=").append(metadata.marketRegime.trim().uppercase()).append('\n')
        append("marketRegimeConfidence=").append(metadata.marketRegimeConfidence).append('\n')
        append("breakoutDirection=").append(metadata.breakoutDirection.trim().uppercase()).append('\n')
        append("oiChange=").append(metadata.openInterestChangePct?.toString() ?: "NA").append('\n')
        append("spreadPct=").append(metadata.spreadPct?.toString() ?: "NA").append('\n')
        append("liquidityScore=").append(metadata.liquidityScore?.toString() ?: "NA").append('\n')
        append("slippageSensitivity=").append(metadata.slippageSensitivity?.toString() ?: "NA").append('\n')
        append("structureDistanceAtr=").append(metadata.structureDistanceAtr?.toString() ?: "NA").append('\n')
        append("contractRiskPct=").append(metadata.contractRiskPct?.toString() ?: "NA").append('\n')
        append("mtfLabel=").append(metadata.mtfConsensusLabel.trim().uppercase()).append('\n')
        append("rr1=").append(metadata.riskReward1?.toString() ?: "NA").append('\n')
        append("rr2=").append(metadata.riskReward2?.toString() ?: "NA").append('\n')
        append("reasons=").append(metadata.reasonCodes.map { it.trim().uppercase() }.filter { it.isNotBlank() }.sorted().joinToString(",")).append('\n')
        append("breakdown=").append(metadata.scoreBreakdown.map { it.trim() }.filter { it.isNotBlank() }.joinToString("|"))
    })

    fun snapshotHash(metadata: V533SnapshotMetadata, itemRefs: List<V533SnapshotItemRef>): String {
        val items = itemRefs.sortedWith(compareBy({ it.symbol.trim().uppercase() }, { it.calculationInputHash }, { it.calculationOutputHash }))
            .joinToString(",") { "${it.symbol.trim().uppercase()}:${it.calculationInputHash.lowercase()}:${it.calculationOutputHash.lowercase()}" }
        return sha256Hex(buildString {
            append("provider=").append(metadata.provider.trim()).append('\n')
            append("providerId=").append(metadata.providerId.trim()).append('\n')
            append("providerVersion=").append(metadata.providerVersion.trim()).append('\n')
            append("sourceType=").append(metadata.sourceType.trim().uppercase()).append('\n')
            append("snapshotId=").append(metadata.snapshotId.trim()).append('\n')
            append("requestId=").append(metadata.requestId.trim()).append('\n')
            append("generatedAt=").append(metadata.generatedAt).append('\n')
            append("serverTime=").append(metadata.serverTime).append('\n')
            append("tracked=").append(metadata.trackedSymbols).append('\n')
            append("fresh=").append(metadata.freshSymbols).append('\n')
            append("ready=").append(metadata.readySymbols).append('\n')
            append("resultItemCount=").append(metadata.resultItemCount).append('\n')
            append("universeVerified=").append(metadata.universeVerified).append('\n')
            append("universeSymbols=").append(metadata.universeSymbols).append('\n')
            append("freshCoverage=").append(metadata.freshCoveragePct).append('\n')
            append("readyCoverage=").append(metadata.readyCoveragePct).append('\n')
            append("minReadyBars=").append(metadata.minReadyBars).append('\n')
            append("engineVersion=").append(metadata.engineVersion).append('\n')
            append("calcEngine=").append(metadata.calculationEngineVersion.trim()).append('\n')
            append("engineMode=").append(metadata.engineMode.trim().uppercase()).append('\n')
            append("timeframe=").append(metadata.analysisTimeframeMinutes).append('\n')
            append("cadence=").append(metadata.scanCadenceMinutes).append('\n')
            append("scanMode=").append(metadata.scanMode.trim().uppercase()).append('\n')
            append("items=").append(items)
        })
    }
}
