package tr.borsatakip.v5.research

import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

object ResearchHash {
    fun id(prefix: String, vararg values: String): String {
        val payload = values.joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(20)
        return "$prefix:$hex"
    }
}

object PointInTimePolicy {
    fun isEligible(document: ResearchInputDocument, asOfTime: Long): Boolean {
        if (asOfTime <= 0L) return false
        if (document.availableAt <= 0L || document.availableAt > asOfTime) return false
        val published = document.publishedAt ?: return false
        if (published <= 0L || published > asOfTime) return false
        // eventTime is the business/event date, not the information-availability time.
        // A future event announced today is valid evidence today.
        return true
    }
}

object SourceIdentityPolicy {
    private val kapDomains = setOf("kap.org.tr")
    private val regulatorDomains = setOf("spk.gov.tr", "tcmb.gov.tr", "tuik.gov.tr")
    private val aggregatorDomains = setOf("news.google.com")

    private fun host(url: String?): String? = url
        ?.let { runCatching { URI(it).host?.lowercase(Locale.ROOT) }.getOrNull() }
        ?.removePrefix("www.")
        ?.trim('.')
        ?.takeIf { it.isNotBlank() }

    private fun domainMatch(host: String?, domains: Set<String>): Boolean = host != null && domains.any { domain ->
        host == domain || host.endsWith(".$domain")
    }

    fun canonicalSourceId(sourceName: String, url: String?): String {
        val host = host(url)
        if (host != null && !domainMatch(host, aggregatorDomains)) return "host:$host"
        val slug = normalizeText(sourceName).replace(' ', '-')
        return "name:${slug.ifBlank { "unknown" }}"
    }

    fun sourceType(document: ResearchInputDocument): SourceType {
        val host = host(document.url)
        if (domainMatch(host, kapDomains)) return SourceType.KAP
        if (domainMatch(host, regulatorDomains)) return SourceType.REGULATOR

        val source = normalizeText(document.sourceName)
        return when (document.sourceType) {
            SourceType.KAP, SourceType.REGULATOR, SourceType.OFFICIAL_FINANCIAL -> when {
                "yatirimci iliskileri" in source || "investor relations" in source -> SourceType.COMPANY
                "ajans" in source || "agency" in source -> SourceType.NEWS_AGENCY
                source.isNotBlank() -> SourceType.NEWS_MEDIA
                else -> SourceType.UNKNOWN
            }
            SourceType.UNKNOWN -> when {
                "yatirimci iliskileri" in source || "investor relations" in source -> SourceType.COMPANY
                "ajans" in source || "agency" in source -> SourceType.NEWS_AGENCY
                source.isNotBlank() -> SourceType.NEWS_MEDIA
                else -> SourceType.UNKNOWN
            }
            else -> document.sourceType
        }
    }

    fun authoritativeIdentityVerified(document: ResearchInputDocument, type: SourceType): Boolean {
        if (!document.providerVerified) return false
        val host = host(document.url)
        return when (type) {
            SourceType.KAP -> domainMatch(host, kapDomains)
            SourceType.REGULATOR -> domainMatch(host, regulatorDomains)
            // Generic OFFICIAL_FINANCIAL identity cannot be proven from a label alone.
            // Local fallback therefore never promotes it to authoritative verification.
            SourceType.OFFICIAL_FINANCIAL -> false
            else -> false
        }
    }

    fun quality(document: ResearchInputDocument, type: SourceType, asOfTime: Long): SourceQuality {
        val authority = when (type) {
            SourceType.KAP, SourceType.REGULATOR, SourceType.OFFICIAL_FINANCIAL -> 98.0
            SourceType.COMPANY -> 92.0
            SourceType.MARKET_DATA -> 90.0
            SourceType.NEWS_AGENCY -> 82.0
            SourceType.NEWS_MEDIA -> 72.0
            SourceType.SOCIAL_MEDIA -> 35.0
            SourceType.UNKNOWN -> 45.0
        }
        val originality = when (type) {
            SourceType.KAP, SourceType.REGULATOR, SourceType.OFFICIAL_FINANCIAL, SourceType.COMPANY -> 96.0
            SourceType.NEWS_AGENCY -> 82.0
            SourceType.MARKET_DATA -> 90.0
            SourceType.NEWS_MEDIA -> 62.0
            SourceType.SOCIAL_MEDIA -> 30.0
            SourceType.UNKNOWN -> 40.0
        }
        val age = (asOfTime - (document.publishedAt ?: 0L)).coerceAtLeast(0L)
        val freshness = when {
            document.publishedAt == null || document.publishedAt <= 0L -> 0.0
            age <= 60 * 60 * 1000L -> 100.0
            age <= 24 * 60 * 60 * 1000L -> 92.0
            age <= 7 * 24 * 60 * 60 * 1000L -> 78.0
            age <= 30L * 24 * 60 * 60 * 1000L -> 58.0
            else -> 35.0
        }
        return SourceQuality(
            authorityScore = authority,
            originalityScore = originality,
            independenceScore = 70.0,
            freshnessScore = freshness,
            claimRelevanceScore = 100.0,
            historicalAccuracyScore = null,
            parsingConfidence = if (document.title.isNotBlank() && document.sourceName.isNotBlank()) 95.0 else 50.0
        )
    }

    fun normalizeText(value: String): String = value
        .lowercase(Locale.forLanguageTag("tr-TR"))
        .replace(Regex("[^a-z0-9çğıöşü]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

object EventClassifier {
    private val rules = listOf(
        EventType.CONTRACT to listOf("sozlesme", "sözleşme", "kontrat"),
        EventType.CAPITAL_INCREASE to listOf("bedelli", "sermaye artirimi", "sermaye artırımı"),
        EventType.BONUS_ISSUE to listOf("bedelsiz"),
        EventType.DIVIDEND to listOf("temettu", "temettü", "kar payi", "kâr payı"),
        EventType.INVESTMENT to listOf("yatirim", "yatırım", "tesis"),
        EventType.LAWSUIT to listOf("dava", "mahkeme"),
        EventType.PENALTY to listOf("ceza", "idari para"),
        EventType.CREDIT to listOf("kredi", "finansman"),
        EventType.ACQUISITION to listOf("satin alma", "satın alma", "devral"),
        EventType.MERGER to listOf("birlesme", "birleşme"),
        EventType.TENDER to listOf("ihale"),
        EventType.ORDER to listOf("siparis", "sipariş"),
        EventType.FINANCIAL_RESULT to listOf("bilanco", "bilanço", "finansal sonuc", "finansal sonuç", "net kar", "net kâr"),
        EventType.MANAGEMENT to listOf("yonetim kurulu", "yönetim kurulu", "genel mudur", "genel müdür")
    )

    fun classify(title: String, summary: String?): EventType {
        val haystack = SourceIdentityPolicy.normalizeText(title + " " + summary.orEmpty())
        return rules.firstOrNull { (_, words) -> words.any { SourceIdentityPolicy.normalizeText(it) in haystack } }?.first
            ?: EventType.OTHER
    }
}

private object ResearchTextMatch {
    private val stopwords = setOf(
        "yeni", "sirket", "şirket", "icin", "için", "ile", "ve", "bir", "bu", "da", "de",
        "acikladi", "açıkladı", "aciklandi", "açıklandı", "alindi", "alındı", "oldu", "olarak"
    )
    private val suffixes = listOf(
        "lerinin", "larının", "leri", "ları", "lerin", "ların", "ler", "lar",
        "dir", "dır", "dur", "dür", "tir", "tır", "tur", "tür",
        "nin", "nın", "nun", "nün", "in", "ın", "un", "ün", "yi", "yı", "yu", "yü", "i", "ı", "u", "ü"
    )

    fun tokens(value: String): Set<String> = SourceIdentityPolicy.normalizeText(value)
        .split(' ')
        .filter { it.length >= 3 }
        .toSet()

    private fun stem(token: String): String {
        suffixes.forEach { suffix ->
            if (token.length > suffix.length + 3 && token.endsWith(suffix)) return token.dropLast(suffix.length)
        }
        return token
    }

    fun significant(value: String): Set<String> = tokens(value)
        .filterNot { it in stopwords }
        .map(::stem)
        .filter { it.length >= 3 }
        .toSet()

    fun numbers(value: String): Set<String> = Regex("\\b\\d+(?:[.,]\\d+)?\\b").findAll(value).map { it.value }.toSet()

    fun similarity(a: String, b: String): Double {
        val left = tokens(a); val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.union(right).size.toDouble()
    }

    fun timeClose(a: ResearchInputDocument, b: ResearchInputDocument, hours: Long = 72): Boolean {
        val pa = a.publishedAt ?: return false
        val pb = b.publishedAt ?: return false
        return pa > 0L && pb > 0L && abs(pa - pb) <= hours * 60L * 60L * 1000L
    }
}

private fun components(size: Int, shouldUnion: (Int, Int) -> Boolean): List<List<Int>> {
    if (size == 0) return emptyList()
    val parent = IntArray(size) { it }
    fun find(x: Int): Int {
        var n = x
        while (parent[n] != n) { parent[n] = parent[parent[n]]; n = parent[n] }
        return n
    }
    fun union(a: Int, b: Int) {
        val ra = find(a); val rb = find(b)
        if (ra != rb) parent[rb] = ra
    }
    for (i in 0 until size) for (j in i + 1 until size) if (shouldUnion(i, j)) union(i, j)
    return (0 until size).groupBy { find(it) }.values.toList()
}

object SyndicationDetector {
    data class Grouping(val groupByDocumentId: Map<String, String>, val duplicateDocumentIds: Set<String>)

    fun group(documents: List<ResearchInputDocument>): Grouping {
        if (documents.isEmpty()) return Grouping(emptyMap(), emptySet())
        val canonical = documents.map { SourceIdentityPolicy.canonicalSourceId(it.sourceName, it.url) }
        val origin = documents.map { it.originSourceId?.trim()?.takeIf(String::isNotBlank) }

        val independentComponents = components(documents.size) { i, j ->
            val a = documents[i]; val b = documents[j]
            val sameCanonical = canonical[i] == canonical[j]
            val sameOrigin = origin[i] != null && origin[i] == origin[j]
            val sameUrl = !a.url.isNullOrBlank() && a.url == b.url
            val syndicated = ResearchTextMatch.timeClose(a, b) && ResearchTextMatch.similarity(a.title, b.title) >= 0.82
            sameCanonical || sameOrigin || sameUrl || syndicated
        }
        val groupMap = mutableMapOf<String, String>()
        independentComponents.forEach { indices ->
            val anchors = indices.map { origin[it] ?: canonical[it] }.distinct().sorted()
            val gid = ResearchHash.id("ind", *anchors.toTypedArray())
            indices.forEach { groupMap[documents[it].documentId] = gid }
        }

        val duplicates = mutableSetOf<String>()
        val duplicateComponents = components(documents.size) { i, j ->
            val a = documents[i]; val b = documents[j]
            val sameUrl = !a.url.isNullOrBlank() && a.url == b.url
            val sameOrigin = origin[i] != null && origin[i] == origin[j]
            val syndicated = ResearchTextMatch.timeClose(a, b) && ResearchTextMatch.similarity(a.title, b.title) >= 0.82
            sameUrl || sameOrigin || syndicated
        }
        duplicateComponents.filter { it.size > 1 }.forEach { indices ->
            val keeper = indices.maxByOrNull { sourcePriority(documents[it]) } ?: indices.first()
            indices.filter { it != keeper }.forEach { duplicates += documents[it].documentId }
        }
        return Grouping(groupMap.toMap(), duplicates.toSet())
    }

    private fun sourcePriority(document: ResearchInputDocument): Double =
        SourceIdentityPolicy.quality(document, SourceIdentityPolicy.sourceType(document), document.receivedAt).authorityScore
}

object ClaimEventResolver {
    data class Resolution(
        val claimIdByDocument: Map<String, String>,
        val eventIdByDocument: Map<String, String>
    )

    fun resolve(documents: List<ResearchInputDocument>, symbol: String): Resolution {
        if (documents.isEmpty()) return Resolution(emptyMap(), emptyMap())

        val claimMap = mutableMapOf<String, String>()
        val claimComponents = components(documents.size) { i, j ->
            val a = documents[i]; val b = documents[j]
            val ak = a.claimKey?.trim().orEmpty()
            val bk = b.claimKey?.trim().orEmpty()
            if (ak.isNotBlank() || bk.isNotBlank()) {
                ak.isNotBlank() && bk.isNotBlank() && SourceIdentityPolicy.normalizeText(ak) == SourceIdentityPolicy.normalizeText(bk)
            } else {
                val at = EventClassifier.classify(a.title, a.summary)
                val bt = EventClassifier.classify(b.title, b.summary)
                if (at != bt || !ResearchTextMatch.timeClose(a, b)) false
                else if (at == EventType.OTHER) ResearchTextMatch.similarity(a.title, b.title) >= 0.60
                else {
                    val overlap = ResearchTextMatch.significant(a.title).intersect(ResearchTextMatch.significant(b.title))
                    val numsA = ResearchTextMatch.numbers(a.title); val numsB = ResearchTextMatch.numbers(b.title)
                    ResearchTextMatch.similarity(a.title, b.title) >= 0.30 || overlap.size >= 2 || (numsA.isNotEmpty() && numsB.isNotEmpty() && numsA.intersect(numsB).isNotEmpty())
                }
            }
        }
        claimComponents.forEach { indices ->
            val explicit = indices.mapNotNull { documents[it].claimKey?.trim()?.takeIf(String::isNotBlank) }
                .map(SourceIdentityPolicy::normalizeText).distinct().sorted()
            val identity = if (explicit.isNotEmpty()) {
                "explicit:${explicit.first()}"
            } else {
                val types = indices.map { EventClassifier.classify(documents[it].title, documents[it].summary).name }.distinct().sorted()
                val common = indices.map { ResearchTextMatch.significant(documents[it].title) }
                    .reduceOrNull { acc, set -> acc.intersect(set) }.orEmpty().sorted().take(6)
                val numbers = indices.flatMap { ResearchTextMatch.numbers(documents[it].title) }.distinct().sorted().take(4)
                val earliest = indices.mapNotNull { documents[it].publishedAt }.minOrNull() ?: 0L
                (types + common + numbers + (earliest / 86_400_000L).toString()).joinToString("|")
            }
            val claimId = ResearchHash.id("claim", symbol, identity)
            indices.forEach { claimMap[documents[it].documentId] = claimId }
        }

        val eventMap = mutableMapOf<String, String>()
        val eventComponents = components(documents.size) { i, j ->
            val a = documents[i]; val b = documents[j]
            val ak = a.eventKey?.trim().orEmpty(); val bk = b.eventKey?.trim().orEmpty()
            if (ak.isNotBlank() || bk.isNotBlank()) {
                ak.isNotBlank() && bk.isNotBlank() && SourceIdentityPolicy.normalizeText(ak) == SourceIdentityPolicy.normalizeText(bk)
            } else {
                val at = EventClassifier.classify(a.title, a.summary)
                val bt = EventClassifier.classify(b.title, b.summary)
                if (at != bt || !ResearchTextMatch.timeClose(a, b)) false
                else if (claimMap[a.documentId] == claimMap[b.documentId]) true
                else {
                    val overlap = ResearchTextMatch.significant(a.title).intersect(ResearchTextMatch.significant(b.title))
                    val numsA = ResearchTextMatch.numbers(a.title); val numsB = ResearchTextMatch.numbers(b.title)
                    overlap.isNotEmpty() || (numsA.isNotEmpty() && numsB.isNotEmpty() && numsA.intersect(numsB).isNotEmpty())
                }
            }
        }
        eventComponents.forEach { indices ->
            val explicit = indices.mapNotNull { documents[it].eventKey?.trim()?.takeIf(String::isNotBlank) }
                .map(SourceIdentityPolicy::normalizeText).distinct().sorted()
            val identity = if (explicit.isNotEmpty()) {
                "explicit:${explicit.first()}"
            } else {
                val types = indices.map { EventClassifier.classify(documents[it].title, documents[it].summary).name }.distinct().sorted()
                val earliest = indices.mapNotNull { documents[it].publishedAt }.minOrNull() ?: 0L
                val claims = indices.map { claimMap.getValue(documents[it].documentId) }.distinct().sorted()
                (types + (earliest / 86_400_000L).toString() + claims).joinToString("|")
            }
            val eventId = ResearchHash.id("event", symbol, identity)
            indices.forEach { eventMap[documents[it].documentId] = eventId }
        }
        return Resolution(claimMap.toMap(), eventMap.toMap())
    }
}

object ResearchMath {
    fun average(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.sum() / values.size
    fun bounded(value: Double): Double = max(0.0, kotlin.math.min(100.0, value))
}
