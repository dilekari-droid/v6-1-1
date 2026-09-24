package tr.borsatakip.v5.research

import tr.borsatakip.v5.model.NewsItem

object NewsResearchAdapter {
    fun toInputs(items: List<NewsItem>, fetchedAt: Long = System.currentTimeMillis()): List<ResearchInputDocument> = items.map { item ->
        val mappedType = runCatching { SourceType.valueOf(item.sourceType.orEmpty().trim().uppercase()) }.getOrDefault(SourceType.UNKNOWN)
        ResearchInputDocument(
            documentId = item.id,
            symbol = item.symbol,
            category = item.category,
            title = item.title,
            summary = item.summary,
            sourceName = item.source,
            url = item.url,
            publishedAt = item.publishedAt.takeIf { it > 0L },
            eventTime = item.eventTime,
            receivedAt = item.receivedAt?.takeIf { it > 0L } ?: fetchedAt,
            availableAt = item.availableAt?.takeIf { it > 0L } ?: item.receivedAt?.takeIf { it > 0L } ?: fetchedAt,
            sourceType = mappedType,
            providerVerified = item.verified,
            supportsClaim = item.supportsClaim,
            claimKey = item.claimKey,
            eventKey = item.eventKey,
            originSourceId = item.originSourceId
        )
    }
}
