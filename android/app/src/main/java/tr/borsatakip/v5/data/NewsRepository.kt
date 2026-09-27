package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.model.NewsItem
import java.io.StringReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Haberler için kullanıcıdan teknik URL istemeyen repository.
 * 1) Yapılandırılmış production backend varsa onu dener.
 * 2) Başarısız/boş ise anahtarsız HTTPS Google News RSS aramasına düşer.
 * 3) Ağ başarısızsa son başarılı cache'i döndürür.
 * Sahte haber üretmez.
 */
class NewsRepository(private val context: Context) {
    data class Result(val items: List<NewsItem>, val source: String, val fromCache: Boolean, val updatedAt: Long)

    private val backend = BackendProvider(context)
    private val prefs = context.getSharedPreferences("news_cache_v2", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun load(symbol: String?, companyName: String?, category: String, forceRefresh: Boolean = false): kotlin.Result<Result> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedCategory = category.uppercase(Locale.ROOT)
            val key = cacheKey(symbol, normalizedCategory)
            val cached = readCache(key)
            val now = System.currentTimeMillis()
            if (!forceRefresh && cached != null && now - cached.updatedAt <= CACHE_TTL_MS) return@runCatching cached.copy(fromCache = true)

            val backendItems = backend.loadNews(symbol, normalizedCategory).getOrNull().orEmpty()
            if (backendItems.isNotEmpty()) {
                val r = Result(dedupe(backendItems), "Production backend", false, now)
                writeCache(key, r)
                return@runCatching r
            }

            val rssItems = loadGoogleNewsRss(symbol, companyName, normalizedCategory)
            if (rssItems.isNotEmpty()) {
                val r = Result(dedupe(rssItems), "Google Haberler RSS", false, now)
                writeCache(key, r)
                return@runCatching r
            }

            cached?.copy(fromCache = true) ?: Result(emptyList(), "Haber sağlayıcısı", false, now)
        }.recoverCatching { error ->
            val cached = readCache(cacheKey(symbol, category.uppercase(Locale.ROOT)))
            cached?.copy(fromCache = true) ?: throw error
        }
    }

    private fun loadGoogleNewsRss(symbol: String?, companyName: String?, category: String): List<NewsItem> {
        val baseTerms = buildList {
            symbol?.takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.ROOT)) }
            companyName?.takeIf { it.isNotBlank() }?.let { add("\"${it.replace("\"", "")}\"") }
        }.ifEmpty { listOf("Borsa İstanbul") }
        val query = when (category) {
            "KAP" -> baseTerms.joinToString(" OR ") + " KAP"
            "ŞİRKET", "SIRKET" -> baseTerms.joinToString(" OR ")
            "SEKTÖR", "SEKTOR" -> baseTerms.joinToString(" OR ") + " sektör"
            "PİYASA", "PIYASA" -> "Borsa İstanbul piyasa"
            else -> baseTerms.joinToString(" OR ")
        }
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://news.google.com/rss/search?q=$encoded&hl=tr&gl=TR&ceid=TR:tr"
        val request = Request.Builder().url(url).header("User-Agent", "BorsaTakip/${BuildConfig.VERSION_NAME} Android").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Haber sağlayıcısı HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) error("Haber sağlayıcısı boş yanıt döndürdü")
            return parseRss(body, symbol, category)
        }
    }

    private fun parseRss(xml: String, symbol: String?, category: String): List<NewsItem> {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(xml)) }
        val out = mutableListOf<NewsItem>()
        var event = parser.eventType
        var inItem = false
        var title: String? = null; var link: String? = null; var pubDate: String? = null; var source: String? = null; var description: String? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase(Locale.ROOT)) {
                        "item" -> { inItem = true; title = null; link = null; pubDate = null; source = null; description = null }
                        "title" -> if (inItem) title = parser.nextText().trim()
                        "link" -> if (inItem) link = parser.nextText().trim()
                        "pubdate" -> if (inItem) pubDate = parser.nextText().trim()
                        "source" -> if (inItem) source = parser.nextText().trim()
                        "description" -> if (inItem) description = stripHtml(parser.nextText()).trim()
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name.equals("item", true)) {
                    inItem = false
                    val t = title.orEmpty()
                    val https = link?.takeIf { it.startsWith("https://") }
                    val published = parseDate(pubDate) ?: System.currentTimeMillis()
                    if (t.isNotBlank() && https != null) {
                        val sourceName = source?.takeIf { it.isNotBlank() } ?: t.substringAfterLast(" - ", "Google Haberler")
                        out += NewsItem(
                            id = "rss:${https.hashCode()}:$published",
                            symbol = symbol?.uppercase(Locale.ROOT),
                            category = category,
                            title = t,
                            summary = description?.takeIf { it.isNotBlank() },
                            source = sourceName,
                            publishedAt = published,
                            url = https,
                            verified = false,
                            receivedAt = System.currentTimeMillis(),
                            availableAt = System.currentTimeMillis(),
                            sourceType = "NEWS_MEDIA",
                            originSourceId = "publisher:" + sourceName.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9çğıöşü]+"), "-").trim('-')
                        )
                    }
                }
            }
            event = parser.next()
        }
        return out.sortedByDescending { it.publishedAt }.take(80)
    }

    private fun parseDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val formats = listOf("EEE, dd MMM yyyy HH:mm:ss z", "EEE, dd MMM yyyy HH:mm:ss Z")
        for (p in formats) runCatching {
            val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return f.parse(value)?.time
        }
        return null
    }

    private fun stripHtml(s: String): String = s.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").replace(Regex("\\s+"), " ")

    private fun dedupe(items: List<NewsItem>): List<NewsItem> = items
        .distinctBy { (it.url ?: "") + "|" + it.title.lowercase(Locale.ROOT) }
        .sortedByDescending { it.publishedAt }

    private fun cacheKey(symbol: String?, category: String) = "${symbol.orEmpty().uppercase(Locale.ROOT)}|$category"

    private fun writeCache(key: String, result: Result) {
        val a = JSONArray()
        result.items.forEach { x ->
            a.put(JSONObject().apply {
                put("id", x.id); put("symbol", x.symbol); put("category", x.category); put("title", x.title); put("summary", x.summary)
                put("source", x.source); put("publishedAt", x.publishedAt); put("url", x.url); put("verified", x.verified)
                put("receivedAt", x.receivedAt); put("availableAt", x.availableAt); put("eventTime", x.eventTime)
                put("claimKey", x.claimKey); put("eventKey", x.eventKey); put("sourceType", x.sourceType); put("originSourceId", x.originSourceId); put("supportsClaim", x.supportsClaim)
            })
        }
        prefs.edit().putString("items:$key", a.toString()).putLong("at:$key", result.updatedAt).putString("source:$key", result.source).apply()
    }

    private fun readCache(key: String): Result? = runCatching {
        val raw = prefs.getString("items:$key", null) ?: return null
        val at = prefs.getLong("at:$key", 0L)
        val source = prefs.getString("source:$key", "Cache") ?: "Cache"
        val a = JSONArray(raw)
        val items = buildList {
            for (i in 0 until a.length()) {
                val x = a.getJSONObject(i)
                add(NewsItem(
                    id = x.getString("id"), symbol = x.optString("symbol").takeIf { it.isNotBlank() }, category = x.optString("category", "ALL"),
                    title = x.getString("title"), summary = x.optString("summary").takeIf { it.isNotBlank() }, source = x.optString("source", "Bilinmiyor"),
                    publishedAt = x.getLong("publishedAt"), url = x.optString("url").takeIf { it.startsWith("https://") }, verified = x.optBoolean("verified", false),
                    receivedAt = x.optLong("receivedAt", 0L).takeIf { it > 0L }, availableAt = x.optLong("availableAt", 0L).takeIf { it > 0L },
                    eventTime = x.optLong("eventTime", 0L).takeIf { it > 0L }, claimKey = x.optString("claimKey").takeIf { it.isNotBlank() },
                    eventKey = x.optString("eventKey").takeIf { it.isNotBlank() }, sourceType = x.optString("sourceType").takeIf { it.isNotBlank() },
                    originSourceId = x.optString("originSourceId").takeIf { it.isNotBlank() }, supportsClaim = x.optBoolean("supportsClaim", true)
                ))
            }
        }
        Result(items.sortedByDescending { it.publishedAt }, source, true, at)
    }.getOrNull()

    companion object { private const val CACHE_TTL_MS = 10 * 60 * 1000L }
}
