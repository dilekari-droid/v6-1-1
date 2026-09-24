package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Optional short-lived backend session bootstrap. The long-lived API key is used only to mint
 * a bounded BorsaSession token; normal requests prefer that token through BackendRequestSecurity.
 * If the server does not advertise shortLivedSessionAuth, callers keep the existing Bearer flow.
 */
class BackendSessionClient(context: Context) {
    companion object {
        /** Process-wide single-flight: concurrent 401/capability callers mint at most one session. */
        private val refreshGate = SessionRefreshGate()
    }
    private val settings = SettingsStore(context.applicationContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun refreshIfNeeded(enabled: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            refreshGate.run(
                isSatisfied = { enabled && settings.backendSessionToken.isNotBlank() },
                refresh = {
                    if (!enabled) {
                        settings.clearBackendSessionToken()
                        false
                    } else {
                        val base = settings.baseUrl.trim().trimEnd('/')
                        require(ProviderReadinessService.isValidHttps(base)) { "HTTPS backend adresi gerekli." }
                        val key = settings.apiKey
                        require(key.isNotBlank()) { "Backend API anahtarı gerekli." }
                        val request = Request.Builder()
                            .url("$base/v1/auth/session")
                            .header("Accept", "application/json")
                            .header("Authorization", "Bearer $key")
                            .header("X-Install-ID", settings.installationId)
                            .post("{}".toRequestBody("application/json".toMediaType()))
                            .build()
                        client.newCall(request).execute().use { response ->
                            val body = response.body?.string().orEmpty()
                            require(response.isSuccessful) { "Session auth HTTP ${response.code}: ${body.take(240)}" }
                            val json = JSONObject(body)
                            val token = json.optString("accessToken")
                            val expiresAt = json.optLong("expiresAt", 0L)
                            require(token.isNotBlank() && expiresAt > System.currentTimeMillis() + SettingsStore.BACKEND_SESSION_RENEW_WINDOW_MS) {
                                "Geçersiz kısa ömürlü session token yanıtı."
                            }
                            check(settings.saveBackendSessionToken(token, expiresAt)) { "Session token güvenli depoya yazılamadı." }
                            true
                        }
                    }
                }
            )
        }
    }
}
