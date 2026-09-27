package tr.borsatakip.v5.research

enum class ResearchResultSource {
    BACKEND,
    FALLBACK,
    LOCAL_ENGINE
}

enum class ResearchStatus {
    SEARCHING,
    COLLECTING,
    VERIFYING,
    ANALYZING,
    CONTRADICTION_CHECK,
    SCENARIO_ANALYSIS,
    COMPLETED,
    PARTIAL,
    INSUFFICIENT,
    FAILED,
    CANCELLED
}

enum class VerificationStatus {
    VERIFIED,
    PARTIALLY_VERIFIED,
    UNVERIFIED,
    CONTRADICTED,
    INSUFFICIENT
}

enum class SourceType {
    KAP,
    COMPANY,
    REGULATOR,
    OFFICIAL_FINANCIAL,
    NEWS_AGENCY,
    NEWS_MEDIA,
    MARKET_DATA,
    SOCIAL_MEDIA,
    UNKNOWN
}

enum class EventType {
    CONTRACT,
    CAPITAL_INCREASE,
    BONUS_ISSUE,
    DIVIDEND,
    INVESTMENT,
    LAWSUIT,
    PENALTY,
    CREDIT,
    ACQUISITION,
    MERGER,
    TENDER,
    ORDER,
    FINANCIAL_RESULT,
    MANAGEMENT,
    OTHER
}

data class SourceQuality(
    val authorityScore: Double,
    val originalityScore: Double,
    val independenceScore: Double,
    val freshnessScore: Double,
    val claimRelevanceScore: Double,
    val historicalAccuracyScore: Double?,
    val parsingConfidence: Double
) {
    init {
        listOf(authorityScore, originalityScore, independenceScore, freshnessScore, claimRelevanceScore, parsingConfidence)
            .forEach { require(it.isFinite() && it in 0.0..100.0) }
        historicalAccuracyScore?.let { require(it.isFinite() && it in 0.0..100.0) }
    }
}

data class ResearchEvent(
    val eventId: String,
    val eventType: EventType,
    val symbol: String,
    val eventTime: Long?,
    val publishedAt: Long?,
    val firstSeenAt: Long,
    val relatedClaimIds: List<String>,
    val relatedEvidenceIds: List<String>
)

data class ResearchClaim(
    val claimId: String,
    val eventId: String?,
    val text: String,
    val status: VerificationStatus,
    val supportingEvidenceIds: List<String>,
    val contradictingEvidenceIds: List<String>,
    val confidence: Double
) {
    init { require(confidence.isFinite() && confidence in 0.0..100.0) }
}

data class Evidence(
    val evidenceId: String,
    val sourceId: String,
    val canonicalSourceId: String,
    val originSourceId: String?,
    val sourceType: SourceType,
    val sourceName: String,
    val title: String,
    val url: String?,
    val eventTime: Long?,
    val publishedAt: Long?,
    val receivedAt: Long,
    val parsedAt: Long,
    val availableAt: Long,
    val quality: SourceQuality,
    val independenceGroupId: String,
    val claimIds: List<String>,
    val supportsClaim: Boolean,
    val providerVerified: Boolean,
    val sourceIdentityVerified: Boolean = false,
    val temporalEligible: Boolean
)

data class EvidenceGraph(
    val events: List<ResearchEvent>,
    val claims: List<ResearchClaim>,
    val evidence: List<Evidence>,
    val rejectedEvidenceIds: List<String>,
    val duplicateEvidenceIds: List<String>
)

data class ConfidenceBreakdown(
    val sourceConfidence: Double,
    val claimConfidence: Double,
    val freshness: Double,
    val dataCompleteness: Double,
    val crossSourceConsistency: Double,
    val parsingConfidence: Double
)

data class ResearchSnapshot(
    val researchId: String,
    val symbol: String,
    val asOfTime: Long,
    val createdAt: Long,
    val status: ResearchStatus,
    val graph: EvidenceGraph,
    val confidence: ConfidenceBreakdown,
    val dataVersion: String,
    val rulesVersion: String,
    val modelVersion: String,
    val sourceSnapshotHash: String,
    val resultSource: ResearchResultSource = ResearchResultSource.LOCAL_ENGINE
)

data class ResearchInputDocument(
    val documentId: String,
    val symbol: String?,
    val category: String,
    val title: String,
    val summary: String?,
    val sourceName: String,
    val url: String?,
    val publishedAt: Long?,
    val eventTime: Long? = null,
    val receivedAt: Long,
    val availableAt: Long,
    val sourceType: SourceType = SourceType.UNKNOWN,
    val providerVerified: Boolean = false,
    val supportsClaim: Boolean = true,
    val claimKey: String? = null,
    val eventKey: String? = null,
    val originSourceId: String? = null
)

object ResearchDataContract {
    const val DATA_VERSION = "5.2.1"
    const val RULES_VERSION = "research-foundation-v5.2.1-rev1"
    const val MODEL_VERSION = "deterministic-v2"
}
