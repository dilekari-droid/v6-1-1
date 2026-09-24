package tr.borsatakip.v5.data

import tr.borsatakip.v5.BuildConfig

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.net.URI
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SettingsStore(c: Context) {
    companion object {
        /** Renew early enough that a request cannot start with a token about to expire. */
        const val BACKEND_SESSION_RENEW_WINDOW_MS = 60_000L
    }
    private val appContext = c.applicationContext
    private val p = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        migrateRetiredBackendHost()
    }

    private fun migrateRetiredBackendHost() {
        val stored = p.getString("base_url", "").orEmpty().trim().removeSuffix("/")
        if (!isRetiredBackendUrl(stored)) return
        p.edit()
            .remove("base_url")
            .remove("api_key_encrypted")
            .remove("api_key_origin")
            .remove("backend_session_token_encrypted")
            .remove("backend_session_token_expiry")
            .remove("backend_session_token_origin")
            .remove("cached_bist_symbols")
            .putInt("cached_bist_symbol_count", 0)
            .putLong("cached_bist_symbols_fetched_at", 0L)
            .putString("cached_bist_symbols_provider_id", "")
            .apply()
        MtfHistoryCache.clear()
        invalidateProviderReadiness()
    }

    var baseUrl: String
        get() {
            val stored = if (p.contains("base_url")) {
                p.getString("base_url", "").orEmpty().trim().removeSuffix("/")
            } else ""
            return stored.ifBlank { BuildConfig.DEFAULT_BACKEND_URL.trim().removeSuffix("/") }
        }
        set(v) {
            val input = v.trim().removeSuffix("/")
            val normalized = if (isRetiredBackendUrl(input)) "" else input
            val oldOrigin = normalizedOrigin(baseUrl)
            val newOrigin = normalizedOrigin(normalized)
            val changed = normalized != baseUrl
            if (changed && oldOrigin.isNotBlank() && oldOrigin != newOrigin) {
                // Bir sunucunun kimlik bilgisi başka origin'e taşınmaz.
                p.edit().remove("api_key_encrypted").remove("api_key_origin").remove("backend_session_token_encrypted").remove("backend_session_token_expiry").remove("backend_session_token_origin").apply()
            }
            p.edit().putString("base_url", normalized).apply()
            if (changed) {
                MtfHistoryCache.clear()
                invalidateProviderReadiness()
            }
        }

    var apiKey: String
        get() {
            val encrypted = getEncrypted("api_key_encrypted")
            if (encrypted.isBlank()) return ""
            val boundOrigin = p.getString("api_key_origin", "").orEmpty()
            return if (boundOrigin.isNotBlank() && boundOrigin == normalizedOrigin(baseUrl)) encrypted else ""
        }
        set(v) {
            val normalized = v.trim()
            val changed = normalized != apiKey
            val stored = putEncrypted("api_key_encrypted", normalized)
            if (!stored) return
            if (normalized.isBlank()) p.edit().remove("api_key_origin").apply()
            else p.edit().putString("api_key_origin", normalizedOrigin(baseUrl)).apply()
            if (changed) {
                clearBackendSessionToken()
                invalidateProviderReadiness()
            }
        }

    fun clearApiKey() {
        p.edit()
            .remove("api_key_encrypted")
            .remove("api_key_origin")
            .remove("backend_session_token_encrypted")
            .remove("backend_session_token_expiry")
            .remove("backend_session_token_origin")
            .apply()
        invalidateProviderReadiness()
    }

    val backendSessionToken: String
        get() {
            val expiry = p.getLong("backend_session_token_expiry", 0L)
            if (expiry <= System.currentTimeMillis() + BACKEND_SESSION_RENEW_WINDOW_MS) return ""
            val boundOrigin = p.getString("backend_session_token_origin", "").orEmpty()
            if (boundOrigin.isBlank() || boundOrigin != normalizedOrigin(baseUrl)) return ""
            return getEncrypted("backend_session_token_encrypted")
        }

    val backendSessionTokenExpiry: Long
        get() = p.getLong("backend_session_token_expiry", 0L)

    fun saveBackendSessionToken(token: String, expiresAt: Long): Boolean {
        val normalized = token.trim()
        if (normalized.isBlank() || expiresAt <= System.currentTimeMillis() + BACKEND_SESSION_RENEW_WINDOW_MS) {
            clearBackendSessionToken()
            return false
        }
        if (!putEncrypted("backend_session_token_encrypted", normalized)) return false
        p.edit()
            .putLong("backend_session_token_expiry", expiresAt)
            .putString("backend_session_token_origin", normalizedOrigin(baseUrl))
            .commit()
        return backendSessionToken.isNotBlank()
    }

    fun clearBackendSessionToken() {
        p.edit()
            .remove("backend_session_token_encrypted")
            .remove("backend_session_token_expiry")
            .remove("backend_session_token_origin")
            .apply()
    }

    /** Per-installation identifier; not a credential and regenerated after app data removal/reinstall. */
    val installationId: String
        get() {
            val existing = p.getString("installation_id", "").orEmpty().trim()
            if (existing.isNotBlank()) return existing
            val created = UUID.randomUUID().toString()
            p.edit().putString("installation_id", created).commit()
            return p.getString("installation_id", created).orEmpty().ifBlank { created }
        }

    var experimentalProvidersEnabled: Boolean
        get() = p.getBoolean("experimental_providers_enabled", false)
        set(v) = p.edit().putBoolean("experimental_providers_enabled", v).apply()

    fun purgeLegacyTradingViewState() {
        p.edit()
            .remove("tv_username")
            .remove("tv_password_encrypted")
            .remove("tv_session_id_encrypted")
            .remove("tv_session_sign_encrypted")
            .remove("tv_auth_token_encrypted")
            .remove("tv_authenticated_at")
            .apply()
    }

    /**
     * Yalnız geçici/cache niteliğindeki verileri temizler. Kimlik bilgileri, kullanıcı
     * tercihleri, favoriler, takip listeleri ve geçmiş kayıtları korunur.
     */
    fun clearTransientCaches(): Boolean {
        val hadSettingsCache = cachedBistSymbols.isNotEmpty() || cachedBistSymbolCount > 0 || cachedBistSymbolsFetchedAt > 0L
        val newsPrefs = appContext.getSharedPreferences("news_cache_v2", Context.MODE_PRIVATE)
        val hadNewsCache = newsPrefs.all.isNotEmpty()
        val hadMtfCache = MtfHistoryCache.hasEntries()
        val hadAny = hadSettingsCache || hadNewsCache || hadMtfCache
        MtfHistoryCache.clear()
        newsPrefs.edit().clear().apply()
        p.edit()
            .remove("cached_bist_symbols")
            .putInt("cached_bist_symbol_count", 0)
            .putLong("cached_bist_symbols_fetched_at", 0L)
            .putString("cached_bist_symbols_provider_id", "")
            .apply()
        invalidateProviderReadiness()
        return hadAny
    }

    /**
     * Kullanıcının operasyonel tercihlerini güvenli varsayılanlara döndürür. Backend
     * origin'i ve Keystore'a bağlı API anahtarı özellikle korunur; böylece reset
     * kimlik bilgisini sessizce kaybetmez. Favori/takip/geçmiş depolarına dokunulmaz.
     */
    fun resetOperationalSettingsPreservingCredentials() {
        p.edit()
            .putBoolean("experimental_providers_enabled", false)
            .putBoolean("yahoo_fallback_enabled", false)
            .putInt("refresh_minutes", 60)
            .putInt("analysis_timeframe_minutes", 5)
            .putInt("scan_cadence_minutes", 1)
            .putBoolean("auto_scan_enabled", false)
            .putString("scan_mode", tr.borsatakip.v5.model.ScanMode.MANUAL.name)
            .putBoolean("forward_outcome_enabled", true)
            .putBoolean("notifications", false)
            .putLong("trade_sim_qty_bits", java.lang.Double.doubleToRawLongBits(0.0))
            .putLong("trade_commission_bits", java.lang.Double.doubleToRawLongBits(0.0))
            .putLong("trade_slippage_bps_bits", java.lang.Double.doubleToRawLongBits(0.0))
            .apply()
    }

    var refreshMinutes: Int
        get() = p.getInt("refresh_minutes", 60).coerceAtLeast(15)
        set(v) = p.edit().putInt("refresh_minutes", v.coerceAtLeast(15)).apply()

    /** Teknik analiz mum periyodu. Tarama/yenileme sıklığından tamamen bağımsızdır. */
    var analysisTimeframeMinutes: Int
        get() = if (p.contains("analysis_timeframe_minutes")) {
            ScanTimeframe.normalizeStoredMinutes(p.getInt("analysis_timeframe_minutes", 5))
        } else {
            // One-time compatibility read for B113 installations created before the split.
            ScanTimeframe.normalizeStoredMinutes(p.getInt("scan_interval_minutes", 5))
        }
        set(v) = p.edit().putInt("analysis_timeframe_minutes", ScanTimeframe.normalizeStoredMinutes(v)).apply()

    /** Otomatik tarama cadence'i. Analiz timeframe'i değildir. */
    var scanCadenceMinutes: Int
        get() = p.getInt("scan_cadence_minutes", 1).coerceIn(1, 1440)
        set(v) = p.edit().putInt("scan_cadence_minutes", v.coerceIn(1, 1440)).apply()

    /** Kullanıcı tarafından açıkça etkinleştirilen arka plan/otomatik BIST taraması. */
    var autoScanEnabled: Boolean
        get() = p.getBoolean("auto_scan_enabled", false)
        set(v) = p.edit().putBoolean("auto_scan_enabled", v).apply()

    var autoScanLastRunAt: Long
        get() = p.getLong("auto_scan_last_run_at", 0L).coerceAtLeast(0L)
        set(v) = p.edit().putLong("auto_scan_last_run_at", v.coerceAtLeast(0L)).apply()

    var autoScanLastStatus: String
        get() = p.getString("auto_scan_last_status", "IDLE") ?: "IDLE"
        set(v) = p.edit().putString("auto_scan_last_status", v.trim().ifBlank { "IDLE" }).apply()

    var autoScanLastMessage: String
        get() = p.getString("auto_scan_last_message", "") ?: ""
        set(v) = p.edit().putString("auto_scan_last_message", v.trim()).apply()

    var autoScanSuccessfulRuns: Int
        get() = p.getInt("auto_scan_successful_runs", 0).coerceAtLeast(0)
        set(v) = p.edit().putInt("auto_scan_successful_runs", v.coerceAtLeast(0)).apply()

    var autoScanFailedRuns: Int
        get() = p.getInt("auto_scan_failed_runs", 0).coerceAtLeast(0)
        set(v) = p.edit().putInt("auto_scan_failed_runs", v.coerceAtLeast(0)).apply()

    var autoScanConsecutiveFailures: Int
        get() = p.getInt("auto_scan_consecutive_failures", 0).coerceAtLeast(0)
        set(v) = p.edit().putInt("auto_scan_consecutive_failures", v.coerceAtLeast(0)).apply()

    var autoScanLastDurationMs: Long
        get() = p.getLong("auto_scan_last_duration_ms", 0L).coerceAtLeast(0L)
        set(v) = p.edit().putLong("auto_scan_last_duration_ms", v.coerceAtLeast(0L)).apply()

    var autoScanLastHeartbeatAt: Long
        get() = p.getLong("auto_scan_last_heartbeat_at", 0L).coerceAtLeast(0L)
        set(v) = p.edit().putLong("auto_scan_last_heartbeat_at", v.coerceAtLeast(0L)).apply()

    /** Foreground otomatik tarama servisinin ekran kilidi/arka plan heartbeat zamanı. */
    var autoScanServiceHeartbeatAt: Long
        get() = p.getLong("auto_scan_service_heartbeat_at", 0L).coerceAtLeast(0L)
        set(v) = p.edit().putLong("auto_scan_service_heartbeat_at", v.coerceAtLeast(0L)).apply()

    /** Canlı WebSocket foreground servis heartbeat zamanı. */
    var realtimeServiceHeartbeatAt: Long
        get() = p.getLong("realtime_service_heartbeat_at", 0L).coerceAtLeast(0L)
        set(v) = p.edit().putLong("realtime_service_heartbeat_at", v.coerceAtLeast(0L)).apply()

    var backgroundNetworkAvailable: Boolean
        get() = p.getBoolean("background_network_available", false)
        set(v) = p.edit().putBoolean("background_network_available", v).apply()

    var backgroundLastNetworkAvailableAt: Long
        get() = p.getLong("background_last_network_available_at", 0L).coerceAtLeast(0L)
        set(v) = p.edit().putLong("background_last_network_available_at", v.coerceAtLeast(0L)).apply()

    var realtimeServiceState: String
        get() = p.getString("realtime_service_state", "IDLE") ?: "IDLE"
        set(v) = p.edit().putString("realtime_service_state", v.trim().ifBlank { "IDLE" }).apply()

    var autoScanBootRescheduleAt: Long
        get() = p.getLong("auto_scan_boot_reschedule_at", 0L).coerceAtLeast(0L)
        set(v) = p.edit().putLong("auto_scan_boot_reschedule_at", v.coerceAtLeast(0L)).apply()

    var autoScanMonitoringStartedAt: Long
        get() = p.getLong("auto_scan_monitoring_started_at", 0L).coerceAtLeast(0L)
        set(v) = p.edit().putLong("auto_scan_monitoring_started_at", v.coerceAtLeast(0L)).apply()

    var scanMode: tr.borsatakip.v5.model.ScanMode
        get() = runCatching {
            tr.borsatakip.v5.model.ScanMode.valueOf(p.getString("scan_mode", tr.borsatakip.v5.model.ScanMode.MANUAL.name) ?: tr.borsatakip.v5.model.ScanMode.MANUAL.name)
        }.getOrDefault(tr.borsatakip.v5.model.ScanMode.MANUAL)
        set(v) = p.edit().putString("scan_mode", v.name).apply()

    @Deprecated("Use analysisTimeframeMinutes; scan cadence is separate")
    var scanIntervalMinutes: Int
        get() = analysisTimeframeMinutes
        set(v) { analysisTimeframeMinutes = v }

    var forwardOutcomeEnabled: Boolean
        get() = p.getBoolean("forward_outcome_enabled", true)
        set(v) = p.edit().putBoolean("forward_outcome_enabled", v).apply()

    var notifications: Boolean
        get() = p.getBoolean("notifications", false)
        set(v) = p.edit().putBoolean("notifications", v).apply()

    var yahooFallbackEnabled: Boolean
        get() = p.getBoolean("yahoo_fallback_enabled", false)
        set(v) = p.edit().putBoolean("yahoo_fallback_enabled", v).apply()

    var tradeSimulationQuantity: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("trade_sim_qty_bits", java.lang.Double.doubleToRawLongBits(0.0))).coerceAtLeast(0.0)
        set(v) = p.edit().putLong("trade_sim_qty_bits", java.lang.Double.doubleToRawLongBits(v.coerceAtLeast(0.0))).apply()

    var tradeCommissionPerSide: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("trade_commission_bits", java.lang.Double.doubleToRawLongBits(0.0))).coerceAtLeast(0.0)
        set(v) = p.edit().putLong("trade_commission_bits", java.lang.Double.doubleToRawLongBits(v.coerceAtLeast(0.0))).apply()

    var tradeSlippageBpsPerSide: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("trade_slippage_bps_bits", java.lang.Double.doubleToRawLongBits(0.0))).coerceAtLeast(0.0)
        set(v) = p.edit().putLong("trade_slippage_bps_bits", java.lang.Double.doubleToRawLongBits(v.coerceAtLeast(0.0))).apply()

    var lastProviderId: String
        get() = p.getString("last_provider_id", "none") ?: "none"
        set(v) = p.edit().putString("last_provider_id", v).apply()

    var lastProviderLabel: String
        get() = p.getString("last_provider_label", "Veri alınmadı") ?: "Veri alınmadı"
        set(v) = p.edit().putString("last_provider_label", v).apply()

    var lastProviderTimestamp: Long
        get() = p.getLong("last_provider_timestamp", 0L)
        set(v) = p.edit().putLong("last_provider_timestamp", v).apply()

    var lastProviderState: String
        get() = p.getString("last_provider_state", "PROVIDER_NOT_CONFIGURED") ?: "PROVIDER_NOT_CONFIGURED"
        set(v) = p.edit().putString("last_provider_state", v).apply()

    var lastProviderFailureCode: String
        get() = p.getString("last_provider_failure_code", "") ?: ""
        set(v) = p.edit().putString("last_provider_failure_code", v).apply()

    var lastProviderMessage: String
        get() = p.getString("last_provider_message", "") ?: ""
        set(v) = p.edit().putString("last_provider_message", v).apply()

    var cachedBistSymbols: Set<String>
        get() = p.getStringSet("cached_bist_symbols", emptySet())?.toSet().orEmpty()
        set(v) = p.edit().putStringSet("cached_bist_symbols", v).apply()

    var cachedBistSymbolCount: Int
        get() = p.getInt("cached_bist_symbol_count", cachedBistSymbols.size)
        set(v) = p.edit().putInt("cached_bist_symbol_count", v.coerceAtLeast(0)).apply()

    var cachedBistSymbolsFetchedAt: Long
        get() = p.getLong("cached_bist_symbols_fetched_at", 0L)
        set(v) = p.edit().putLong("cached_bist_symbols_fetched_at", v.coerceAtLeast(0L)).apply()

    var cachedBistSymbolsProviderId: String
        get() = p.getString("cached_bist_symbols_provider_id", "") ?: ""
        set(v) = p.edit().putString("cached_bist_symbols_provider_id", v).apply()

    var lastBackendHealthAt: Long
        get() = p.getLong("last_backend_health_at", 0L)
        set(v) = p.edit().putLong("last_backend_health_at", v.coerceAtLeast(0L)).apply()

    var lastBackendHealthOk: Boolean
        get() = p.getBoolean("last_backend_health_ok", false)
        set(v) = p.edit().putBoolean("last_backend_health_ok", v).apply()


    /**
     * Backend origin değişirse mevcut API anahtarı güvenlik gereği silinir. UI bu
     * yöntemi kullanarak kaydetmeden önce kullanıcıyı açıkça uyarabilir.
     */
    fun willClearApiKeyOnBackendChange(candidateUrl: String): Boolean {
        if (apiKey.isBlank()) return false
        val normalized = candidateUrl.trim().removeSuffix("/")
        if (normalized == baseUrl) return false
        val oldOrigin = normalizedOrigin(baseUrl)
        val newOrigin = normalizedOrigin(normalized)
        return oldOrigin.isNotBlank() && oldOrigin != newOrigin
    }

    /** API anahtarı içermeyen, cache kimliği için güvenli normalize backend origin'i. */
    fun backendOrigin(): String = normalizedOrigin(baseUrl)

    private fun normalizedOrigin(value: String): String = runCatching {
        val u = URI(value.trim())
        if (!u.scheme.equals("https", true) || u.host.isNullOrBlank()) ""
        else "https://${u.host.lowercase()}${if (u.port > 0 && u.port != 443) ":${u.port}" else ""}"
    }.getOrDefault("")

    private fun invalidateProviderReadiness() {
        p.edit()
            .putString("last_provider_state", "PROVIDER_CONFIGURED")
            .putString("last_provider_failure_code", "")
            .putString("last_provider_message", "Provider ayarları değişti; yeniden bağlantı testi gerekli.")
            .putBoolean("last_backend_health_ok", false)
            .putLong("last_backend_health_at", 0L)
            .apply()
    }

    private fun getEncrypted(key: String): String {
        val encrypted = p.getString(key, null)
        return if (encrypted.isNullOrBlank()) "" else decrypt(encrypted).orEmpty()
    }

    private fun putEncrypted(key: String, value: String): Boolean {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            p.edit().remove(key).apply()
            return true
        }
        val encrypted = encrypt(normalized) ?: return false
        p.edit().putString(key, encrypted).apply()
        return true
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(value: String): String? = runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE)
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        private const val KEY_ALIAS = "borsa_takip_api_key"
        private const val IV_SIZE = 12

        internal fun isRetiredBackendUrl(
            url: String,
            retiredHosts: Set<String> = BuildConfig.RETIRED_BACKEND_HOSTS
                .split(';')
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .toSet()
        ): Boolean {
            val host = runCatching { URI(url.trim()).host?.lowercase().orEmpty() }.getOrDefault("")
            return host.isNotBlank() && host in retiredHosts
        }
    }
}
