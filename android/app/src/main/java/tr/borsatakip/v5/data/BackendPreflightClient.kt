package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Production taraması başlamadan önce backend readiness zincirini tek noktadan doğrular.
 * Config/auth/BIST/VİOP adımlarından biri başarısızsa provider READY sayılmaz.
 */
class BackendPreflightClient(private val context: Context) {
    private val settings = SettingsStore(context)

    enum class FailureKind {
        NONE, BACKEND_NOT_CONFIGURED, API_KEY_MISSING, HTTPS_REQUIRED, INVALID_URL,
        AUTH_ERROR, HTTP_ERROR, RATE_LIMIT, SERVER_ERROR, NETWORK_TIMEOUT, DNS_ERROR, TLS_ERROR,
        EMPTY_DATA, SYMBOLS_ERROR, QUOTE_ERROR, HISTORY_ERROR,
        VIOP_CONTRACTS_ERROR, VIOP_QUOTE_ERROR, VIOP_HISTORY_ERROR, STALE_DATA, INVALID_DATA
    }

    enum class BistAvailabilityMode { REALTIME, DELAYED_ANALYSIS, SESSION_CLOSE, UNAVAILABLE }

    data class Result(
        val ok: Boolean,
        val failureKind: FailureKind,
        val message: String,
        val healthOk: Boolean = false,
        val authOk: Boolean = false,
        val symbolsOk: Boolean = false,
        val quoteOk: Boolean = false,
        val historyOk: Boolean = false,
        val viopContractsOk: Boolean = false,
        val viopQuoteOk: Boolean = false,
        val viopHistoryOk: Boolean = false,
        val symbolCount: Int = 0,
        val viopContractCount: Int = 0,
        val sampleSymbol: String? = null,
        val sampleViopSymbol: String? = null,
        val provider: String? = null,
        val elapsedMs: Long = 0L,
        val bistAvailabilityMode: BistAvailabilityMode = BistAvailabilityMode.UNAVAILABLE,
        val providerAnalysisMode: String = "UNAVAILABLE",
        val referenceServerTime: Long = 0L
    )

    suspend fun check(): Result = checkAll()

    suspend fun checkBist(): Result = checkCore(includeViop = false)

    suspend fun checkAll(): Result = checkCore(includeViop = true)

    private suspend fun checkCore(includeViop: Boolean): Result = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val base = settings.baseUrl.trim().removeSuffix("/")
        if (base.isBlank()) return@withContext fail(FailureKind.BACKEND_NOT_CONFIGURED, "Production Backend yapılandırılmamış. Gerçek HTTPS backend adresi ve API erişim anahtarı girin.", started)
        if (settings.apiKey.isBlank()) return@withContext fail(FailureKind.API_KEY_MISSING, "Production Backend yapılandırılmamış. API erişim anahtarı girin.", started)
        if (!validHttps(base)) return@withContext fail(FailureKind.INVALID_URL, "Geçersiz Production Backend URL. Yalnız geçerli HTTPS adresi kabul edilir.", started)

        val health = requestJson(base, "/v1/health")
        if (!health.ok) return@withContext health.toResult(started, "Health kontrolü başarısız")
        val healthJson = health.json ?: return@withContext fail(FailureKind.INVALID_DATA, "Health cevabı geçersiz.", started)
        val healthOk = healthJson.optBoolean("ok", false)
        val provider = healthJson.optString("provider").ifBlank { "Production Backend" }
        if (!healthOk) return@withContext fail(FailureKind.HTTP_ERROR, healthJson.optString("message").ifBlank { "Backend ayakta değil." }, started, provider = provider)

        // BIST readiness: backend'in merkezi /v1/preflight sözleşmesi.
        val preflight = requestJson(base, "/v1/preflight")
        if (!preflight.ok) return@withContext preflight.toResult(started, "Production preflight başarısız", healthOk = true, provider = provider)
        val json = preflight.json ?: return@withContext fail(FailureKind.INVALID_DATA, "Preflight cevabı geçersiz.", started, healthOk = true, provider = provider)
        fun check(name: String): JSONObject? = json.optJSONObject(name)
        val authOk = check("authentication")?.optBoolean("ok", false) == true
        val symbolsOk = check("symbols")?.optBoolean("ok", false) == true
        val quoteOk = check("quote")?.optBoolean("ok", false) == true
        val historyOk = check("history")?.optBoolean("ok", false) == true
        val symbolCount = json.optInt("symbolCount", 0)
        val sample = json.optString("sampleSymbol").takeIf { it.isNotBlank() }
        val providerAnalysisMode = json.optString("analysisMode").ifBlank { "UNAVAILABLE" }
        val quoteCode = check("quote")?.optString("code").orEmpty()
        val serverTime = json.optLong("serverTime", System.currentTimeMillis()).takeIf { it > 0L } ?: System.currentTimeMillis()
        val sessionPhase = BistSessionClosePolicy.phase(serverTime)
        val sessionCloseAllowed = BistPreflightPolicy.allowSessionClose(
            includeViop = includeViop,
            historyOk = historyOk,
            quoteOk = quoteOk,
            providerAnalysisMode = providerAnalysisMode,
            quoteCode = quoteCode,
            phase = sessionPhase
        )
        val delayedAnalysisAllowed = !includeViop && historyOk && !quoteOk &&
            providerAnalysisMode.equals("DELAYED_ANALYSIS_AVAILABLE", ignoreCase = true) &&
            quoteCode.equals("STALE_DATA", ignoreCase = true)

        if (!authOk) return@withContext fail(FailureKind.AUTH_ERROR, check("authentication")?.optString("message").orEmpty().ifBlank { "Authentication başarısız." }, started, healthOk = true, provider = provider)
        if (!symbolsOk || symbolCount <= 0) return@withContext fail(FailureKind.SYMBOLS_ERROR, check("symbols")?.optString("message").orEmpty().ifBlank { "BIST sembol evreni alınamadı." }, started, true, true, provider = provider, symbolCount = symbolCount)
        if (!historyOk) return@withContext fail(FailureKind.HISTORY_ERROR, check("history")?.optString("message").orEmpty().ifBlank { "BIST history doğrulanamadı." }, started, true, true, true, quoteOk, provider = provider, symbolCount = symbolCount, sampleSymbol = sample)
        if (!quoteOk && !sessionCloseAllowed && !delayedAnalysisAllowed) return@withContext fail(FailureKind.QUOTE_ERROR, check("quote")?.optString("message").orEmpty().ifBlank { "BIST quote doğrulanamadı." }, started, true, true, true, provider = provider, symbolCount = symbolCount, sampleSymbol = sample)
        if (!json.optBoolean("ok", false) && !sessionCloseAllowed && !delayedAnalysisAllowed) return@withContext fail(FailureKind.HISTORY_ERROR, check("history")?.optString("message").orEmpty().ifBlank { "BIST preflight doğrulanamadı." }, started, true, true, true, quoteOk, historyOk, provider = provider, symbolCount = symbolCount, sampleSymbol = sample)

        if (!includeViop) {
            settings.cachedBistSymbolCount = symbolCount
            settings.lastBackendHealthAt = System.currentTimeMillis()
            settings.lastBackendHealthOk = true
            val mode = when {
                sessionCloseAllowed -> BistAvailabilityMode.SESSION_CLOSE
                delayedAnalysisAllowed -> BistAvailabilityMode.DELAYED_ANALYSIS
                else -> BistAvailabilityMode.REALTIME
            }
            val message = when (mode) {
                BistAvailabilityMode.SESSION_CLOSE -> "BIST KAPANIŞ TARAMASI HAZIR • Health ✓ • Authentication ✓ • Symbols ✓ • History ✓ • Canlı quote gerekli değil"
                BistAvailabilityMode.DELAYED_ANALYSIS -> "BIST GECİKMELİ ANALİZ HAZIR • Health ✓ • Authentication ✓ • Symbols ✓ • History ✓ • Canlı quote doğrulanmadı; veri canlı sinyal olarak yayımlanmayacak"
                BistAvailabilityMode.REALTIME -> "BIST REALTIME READY • Health ✓ • Authentication ✓ • Symbols ✓ • Quote ✓ • History ✓"
                BistAvailabilityMode.UNAVAILABLE -> "BIST kullanılamıyor"
            }
            return@withContext Result(
                ok = true, failureKind = FailureKind.NONE, message = message,
                healthOk = true, authOk = true, symbolsOk = true, quoteOk = quoteOk, historyOk = true,
                symbolCount = symbolCount, sampleSymbol = sample, provider = provider,
                elapsedMs = System.currentTimeMillis() - started,
                bistAvailabilityMode = mode, providerAnalysisMode = providerAnalysisMode, referenceServerTime = serverTime
            )
        }

        // VİOP readiness: gerçek provider ile aynı parser/validator ve merkezi kontrat seçimi kullanılır.
        val backendProvider = BackendProvider(context)
        val contractsResult = backendProvider.loadViop()
        if (contractsResult.isFailure) {
            return@withContext fail(FailureKind.VIOP_CONTRACTS_ERROR, "VİOP contract universe başarısız • ${contractsResult.exceptionOrNull()?.message ?: "bilinmeyen hata"}", started, true, true, true, true, true, provider = provider, symbolCount = symbolCount, sampleSymbol = sample)
        }
        val contractUniverse = contractsResult.getOrDefault(emptyList())
        val viopCount = contractUniverse.size
        val candidates = ViopContractSelector.candidates(contractUniverse, allowWatch = false)
        if (candidates.isEmpty()) {
            return@withContext fail(FailureKind.VIOP_CONTRACTS_ERROR, "NO_CONTRACT: Doğrulanmış aktif VİOP sözleşmesi bulunamadı.", started, true, true, true, true, true, provider = provider, symbolCount = symbolCount, viopContractCount = viopCount, sampleSymbol = sample)
        }

        var selectedSymbol: String? = null
        var lastQuoteError: Throwable? = null
        var lastHistoryError: Throwable? = null
        for (candidate in candidates.take(MAX_VIOP_PREFLIGHT_CANDIDATES)) {
            val quote = backendProvider.loadViopQuote(candidate.symbol)
            if (quote.isFailure) {
                lastQuoteError = quote.exceptionOrNull()
                continue
            }
            val history = backendProvider.loadViopHistory(candidate.symbol)
            if (history.isFailure) {
                lastHistoryError = history.exceptionOrNull()
                continue
            }
            selectedSymbol = candidate.symbol
            break
        }
        if (selectedSymbol == null) {
            val detail = lastHistoryError?.message ?: lastQuoteError?.message ?: "Uygun VİOP kontratında quote/history doğrulanamadı."
            val kind = if (lastHistoryError != null) FailureKind.VIOP_HISTORY_ERROR else FailureKind.VIOP_QUOTE_ERROR
            return@withContext fail(kind, detail, started, true, true, true, true, true, true, lastHistoryError == null, provider = provider, symbolCount = symbolCount, viopContractCount = viopCount, sampleSymbol = sample)
        }

        settings.cachedBistSymbolCount = symbolCount
        settings.lastBackendHealthAt = System.currentTimeMillis()
        settings.lastBackendHealthOk = true
        Result(
            ok = true, failureKind = FailureKind.NONE,
            message = "Provider READY • Health ✓ • Authentication ✓ • BIST Symbols ✓ • BIST Quote ✓ • BIST History ✓ • VİOP Contracts ✓ • VİOP Quote ✓ • VİOP History ✓",
            healthOk = true, authOk = true, symbolsOk = true, quoteOk = true, historyOk = true,
            viopContractsOk = true, viopQuoteOk = true, viopHistoryOk = true,
            symbolCount = symbolCount, viopContractCount = viopCount, sampleSymbol = sample, sampleViopSymbol = selectedSymbol,
            provider = provider, elapsedMs = System.currentTimeMillis() - started,
            bistAvailabilityMode = BistAvailabilityMode.REALTIME, providerAnalysisMode = "REALTIME", referenceServerTime = System.currentTimeMillis()
        )
    }

    private data class HttpResult(val ok: Boolean, val code: Int? = null, val json: JSONObject? = null, val kind: FailureKind = FailureKind.NONE, val detail: String = "")

    private fun requestJson(base: String, path: String): HttpResult {
        fun open(): HttpURLConnection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            BackendRequestSecurity.apply(this, settings)
        }
        var con: HttpURLConnection? = null
        return try {
            con = open()
            var code = con.responseCode
            if ((code == 401 || code == 403) && settings.backendSessionToken.isNotBlank()) {
                con.disconnect()
                settings.clearBackendSessionToken()
                con = open()
                code = con.responseCode
            }
            if (code == 401 || code == 403) return HttpResult(false, code, kind = FailureKind.AUTH_ERROR, detail = "Kimlik doğrulama başarısız (HTTP $code).")
            if (code == 429) return HttpResult(false, code, kind = FailureKind.RATE_LIMIT, detail = "İstek sınırı aşıldı (HTTP 429). Lütfen kısa süre sonra yeniden deneyin.")
            if (code >= 500) return HttpResult(false, code, kind = FailureKind.SERVER_ERROR, detail = "Backend sunucu hatası (HTTP $code).")
            if (code !in 200..299) return HttpResult(false, code, kind = FailureKind.HTTP_ERROR, detail = "Backend HTTP $code hatası.")
            val body = con.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) return HttpResult(false, code, kind = FailureKind.EMPTY_DATA, detail = "Backend boş cevap döndürdü.")
            HttpResult(true, code, JSONObject(body))
        } catch (_: SocketTimeoutException) { HttpResult(false, kind = FailureKind.NETWORK_TIMEOUT, detail = "Üretim veri servisi zaman aşımına uğradı.")
        } catch (_: UnknownHostException) { HttpResult(false, kind = FailureKind.DNS_ERROR, detail = "Backend alan adı çözümlenemedi (DNS).")
        } catch (_: SSLException) { HttpResult(false, kind = FailureKind.TLS_ERROR, detail = "Backend TLS/SSL bağlantısı kurulamadı.")
        } catch (e: Exception) { HttpResult(false, kind = FailureKind.HTTP_ERROR, detail = e.message ?: "Üretim veri servisine ulaşılamıyor.")
        } finally { runCatching { con?.disconnect() } }
    }

    private fun quoteFresh(json: JSONObject): Boolean {
        if (!json.optBoolean("realtime", false) || !json.optBoolean("currentSessionIncluded", false)) return false
        val ts = json.optLong("exchangeTimestamp", 0L)
        val delay = json.optInt("delaySeconds", Int.MAX_VALUE)
        val age = System.currentTimeMillis() - ts
        return ts > 0L && delay in 0..5 && age in -15_000L..60_000L
    }

    private fun HttpResult.toResult(started: Long, prefix: String, healthOk: Boolean = false, provider: String? = null): Result =
        fail(kind.takeIf { it != FailureKind.NONE } ?: FailureKind.HTTP_ERROR, "$prefix • ${detail.ifBlank { "Bağlantı başarısız" }}", started, healthOk = healthOk, provider = provider)

    private fun fail(kind: FailureKind, message: String, started: Long,
        healthOk: Boolean = false, authOk: Boolean = false, symbolsOk: Boolean = false,
        quoteOk: Boolean = false, historyOk: Boolean = false, viopContractsOk: Boolean = false,
        viopQuoteOk: Boolean = false, viopHistoryOk: Boolean = false, symbolCount: Int = 0,
        viopContractCount: Int = 0, sampleSymbol: String? = null, sampleViopSymbol: String? = null,
        provider: String? = null): Result {
        settings.lastBackendHealthAt = System.currentTimeMillis()
        settings.lastBackendHealthOk = false
        return Result(false, kind, message, healthOk, authOk, symbolsOk, quoteOk, historyOk,
            viopContractsOk, viopQuoteOk, viopHistoryOk, symbolCount, viopContractCount,
            sampleSymbol, sampleViopSymbol, provider, System.currentTimeMillis() - started)
    }

    companion object {
        fun validHttps(raw: String): Boolean = runCatching {
            val uri = URI(raw.trim())
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null &&
                uri.host !in setOf("localhost", "127.0.0.1", "10.0.2.2")
        }.getOrDefault(false)

        private const val MAX_VIOP_PREFLIGHT_CANDIDATES = 8
    }
}
