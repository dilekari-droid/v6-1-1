package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class BackendHealthClient(context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsStore(appContext)

    data class Health(
        val ok: Boolean,
        val provider: String,
        val latencyMs: Long,
        val serverTime: Long?,
        val message: String
    )

    suspend fun check(): Health = withContext(Dispatchers.IO) {
        val base = settings.baseUrl.trim().removeSuffix("/")
        if (base.isBlank()) return@withContext Health(false, "tanımsız", 0, null, "Servis adresi tanımlı değil.")
        if (!ProviderReadinessService.isValidHttps(base)) return@withContext Health(false, "tanımsız", 0, null, "HTTPS servis adresi gerekli.")

        val start = System.currentTimeMillis()
        fun open(): HttpURLConnection = (URL(base + "/v1/health").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/json")
            BackendRequestSecurity.apply(this, settings)
        }
        var con = open()
        try {
            var code = con.responseCode
            if ((code == 401 || code == 403) && settings.backendSessionToken.isNotBlank()) {
                con.disconnect()
                settings.clearBackendSessionToken()
                BackendSessionClient(appContext).refreshIfNeeded(enabled = true)
                con = open()
                code = con.responseCode
            }
            val latency = System.currentTimeMillis() - start
            if (code !in 200..299) return@withContext Health(false, "bilinmiyor", latency, null, "HTTP $code")
            val body = con.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            Health(
                ok = json.optBoolean("ok", true),
                provider = json.optString("provider").ifBlank { "mobil backend" },
                latencyMs = latency,
                serverTime = json.optLong("serverTime", 0L).takeIf { it > 0 },
                message = json.optString("message").ifBlank { "Bağlantı başarılı" }
            )
        } catch (e: Exception) {
            Health(false, "bilinmiyor", System.currentTimeMillis() - start, null, e.message ?: "Bağlantı kurulamadı")
        } finally {
            con.disconnect()
        }
    }
}
