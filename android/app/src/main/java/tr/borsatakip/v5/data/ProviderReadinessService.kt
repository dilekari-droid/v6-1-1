package tr.borsatakip.v5.data

import android.content.Context
import java.net.URI

enum class ProviderState {
    PROVIDER_NOT_CONFIGURED,
    PROVIDER_CONFIGURED,
    PROVIDER_TESTING,
    PROVIDER_READY,
    PROVIDER_STALE_READY,
    PROVIDER_ERROR
}

enum class ProviderFailureCode {
    NONE,
    BACKEND_URL_MISSING,
    API_KEY_MISSING,
    INVALID_HTTPS,
    TLS_ERROR,
    HEALTH_ERROR,
    AUTH_ERROR,
    BIST_SYMBOLS_ERROR,
    BIST_QUOTE_ERROR,
    BIST_HISTORY_ERROR,
    VIOP_CONTRACTS_ERROR,
    VIOP_QUOTE_ERROR,
    VIOP_HISTORY_ERROR,
    STALE_DATA,
    RATE_LIMIT,
    SERVER_ERROR,
    NETWORK_ERROR,
    DNS_ERROR,
    NETWORK_TIMEOUT,
    EMPTY_DATA,
    UNKNOWN_ERROR
}

data class ProviderReadinessSnapshot(
    val state: ProviderState,
    val failureCode: ProviderFailureCode = ProviderFailureCode.NONE,
    val message: String = "",
    val healthOk: Boolean = false,
    val authOk: Boolean = false,
    val bistSymbolsOk: Boolean = false,
    val bistQuoteOk: Boolean = false,
    val bistHistoryOk: Boolean = false,
    val viopContractsOk: Boolean = false,
    val viopQuoteOk: Boolean = false,
    val viopHistoryOk: Boolean = false,
    val bistSymbolCount: Int = 0,
    val viopContractCount: Int = 0,
    val testedAt: Long = 0L
)

class ProviderReadinessService(context: Context) {
    private val settings = SettingsStore(context)
    private val preflight = BackendPreflightClient(context)

    fun localConfigState(): ProviderReadinessSnapshot {
        val url = settings.baseUrl.trim()
        val key = settings.apiKey.trim()
        if (url.isBlank()) return ProviderReadinessSnapshot(
            ProviderState.PROVIDER_NOT_CONFIGURED,
            ProviderFailureCode.BACKEND_URL_MISSING,
            "Production Backend yapılandırılmamış. Gerçek HTTPS backend adresi ve API erişim anahtarı girin."
        )
        if (key.isBlank()) return ProviderReadinessSnapshot(
            ProviderState.PROVIDER_NOT_CONFIGURED,
            ProviderFailureCode.API_KEY_MISSING,
            "Production Backend yapılandırılmamış. API erişim anahtarı girin."
        )
        if (!isValidHttps(url)) return ProviderReadinessSnapshot(
            ProviderState.PROVIDER_ERROR,
            ProviderFailureCode.INVALID_HTTPS,
            "Geçersiz Production Backend adresi. Yalnız geçerli HTTPS URL kabul edilir."
        )
        val storedState = runCatching { ProviderState.valueOf(settings.lastProviderState) }.getOrNull()
        val storedCode = runCatching { ProviderFailureCode.valueOf(settings.lastProviderFailureCode) }.getOrNull()
        return when (storedState) {
            ProviderState.PROVIDER_READY -> {
                val testedAt = settings.lastBackendHealthAt
                val age = if (testedAt > 0L) System.currentTimeMillis() - testedAt else Long.MAX_VALUE
                if (age > READY_TTL_MS) {
                    ProviderReadinessSnapshot(
                        ProviderState.PROVIDER_STALE_READY,
                        ProviderFailureCode.STALE_DATA,
                        "Provider READY doğrulamasının süresi doldu; tarama öncesi hızlı doğrulama gerekli.",
                        healthOk = settings.lastBackendHealthOk,
                        bistSymbolCount = settings.cachedBistSymbolCount,
                        testedAt = testedAt
                    )
                } else {
                    ProviderReadinessSnapshot(
                        ProviderState.PROVIDER_READY,
                        ProviderFailureCode.NONE,
                        settings.lastProviderMessage.ifBlank { "Production provider hazır." },
                        healthOk = settings.lastBackendHealthOk,
                        bistSymbolCount = settings.cachedBistSymbolCount,
                        testedAt = testedAt
                    )
                }
            }
            ProviderState.PROVIDER_STALE_READY -> {
                val remembered = settings.lastProviderMessage
                val analysisReady = remembered.contains("GECİKMELİ", ignoreCase = true) || remembered.contains("KAPANIŞ", ignoreCase = true)
                ProviderReadinessSnapshot(
                    ProviderState.PROVIDER_STALE_READY,
                    if (analysisReady) ProviderFailureCode.NONE else ProviderFailureCode.STALE_DATA,
                    if (analysisReady) remembered else "Provider doğrulaması eski; yeniden test gerekli.",
                    healthOk = settings.lastBackendHealthOk,
                    bistSymbolCount = settings.cachedBistSymbolCount,
                    testedAt = settings.lastBackendHealthAt
                )
            }
            ProviderState.PROVIDER_ERROR -> ProviderReadinessSnapshot(
                ProviderState.PROVIDER_ERROR,
                storedCode ?: ProviderFailureCode.UNKNOWN_ERROR,
                settings.lastProviderMessage.ifBlank { "Son provider testi başarısız." },
                testedAt = settings.lastBackendHealthAt
            )
            ProviderState.PROVIDER_TESTING -> ProviderReadinessSnapshot(
                ProviderState.PROVIDER_TESTING,
                ProviderFailureCode.NONE,
                "Production provider test ediliyor...",
                testedAt = settings.lastBackendHealthAt
            )
            else -> ProviderReadinessSnapshot(
                ProviderState.PROVIDER_CONFIGURED,
                ProviderFailureCode.NONE,
                "Production Backend yapılandırıldı; bağlantı testi gerekli.",
                testedAt = settings.lastBackendHealthAt
            )
        }
    }

    /**
     * Genel uygulama/BIST readiness testi.
     *
     * VİOP doğrulaması bu kapıya dahil değildir. Böylece VİOP upstream'i yapılandırılmamış
     * olsa bile BIST taraması ve seans-kapanış analizi gereksiz yere PROVIDER_ERROR olmaz.
     */
    suspend fun test(): ProviderReadinessSnapshot = testBist()

    suspend fun testBist(): ProviderReadinessSnapshot {
        val local = localConfigState()
        if (local.state == ProviderState.PROVIDER_NOT_CONFIGURED || local.failureCode == ProviderFailureCode.INVALID_HTTPS) {
            persist(local)
            return local
        }

        settings.lastProviderState = ProviderState.PROVIDER_TESTING.name
        settings.lastProviderFailureCode = ProviderFailureCode.NONE.name
        settings.lastProviderMessage = "BIST provider test ediliyor..."

        val result = preflight.checkBist()
        val snapshot = if (result.ok) {
            val sessionClose = result.bistAvailabilityMode == BackendPreflightClient.BistAvailabilityMode.SESSION_CLOSE
            val delayed = result.bistAvailabilityMode == BackendPreflightClient.BistAvailabilityMode.DELAYED_ANALYSIS
            ProviderReadinessSnapshot(
                state = if (sessionClose || delayed) ProviderState.PROVIDER_STALE_READY else ProviderState.PROVIDER_READY,
                message = when {
                    sessionClose -> "BIST KAPANIŞ MODU HAZIR • HTTPS ✓ • Health ✓ • Authentication ✓ • Symbols ✓ • History ✓ • Canlı quote seans dışında zorunlu değil"
                    delayed -> "BIST GECİKMELİ ANALİZ HAZIR • HTTPS ✓ • Health ✓ • Authentication ✓ • Symbols ✓ • History ✓ • Quote canlı değil; sonuçlar gözlem modunda"
                    else -> "BIST REALTIME PROVIDER HAZIR • HTTPS ✓ • Health ✓ • Authentication ✓ • Symbols ✓ • Quote ✓ • History ✓"
                },
                healthOk = result.healthOk,
                authOk = result.authOk,
                bistSymbolsOk = result.symbolsOk,
                bistQuoteOk = result.quoteOk,
                bistHistoryOk = result.historyOk,
                bistSymbolCount = result.symbolCount,
                testedAt = System.currentTimeMillis()
            )
        } else {
            ProviderReadinessSnapshot(
                state = if (result.failureKind == BackendPreflightClient.FailureKind.BACKEND_NOT_CONFIGURED || result.failureKind == BackendPreflightClient.FailureKind.API_KEY_MISSING) ProviderState.PROVIDER_NOT_CONFIGURED else ProviderState.PROVIDER_ERROR,
                failureCode = mapFailure(result.failureKind),
                message = result.message,
                healthOk = result.healthOk,
                authOk = result.authOk,
                bistSymbolsOk = result.symbolsOk,
                bistQuoteOk = result.quoteOk,
                bistHistoryOk = result.historyOk,
                bistSymbolCount = result.symbolCount,
                testedAt = System.currentTimeMillis()
            )
        }
        persist(snapshot)
        return snapshot
    }

    /**
     * VİOP ekranına özel sıkı doğrulama. BIST + VİOP contracts/quote/history zincirinin
     * tamamı geçmeden PROVIDER_READY döndürmez.
     */
    suspend fun testAll(): ProviderReadinessSnapshot {
        val local = localConfigState()
        if (local.state == ProviderState.PROVIDER_NOT_CONFIGURED || local.failureCode == ProviderFailureCode.INVALID_HTTPS) {
            return local
        }
        val result = preflight.checkAll()
        return if (result.ok) {
            ProviderReadinessSnapshot(
                state = ProviderState.PROVIDER_READY,
                message = "VİOP PROVIDER HAZIR • HTTPS ✓ • Health ✓ • Authentication ✓ • BIST ✓ • VİOP Contracts ✓ • VİOP Quote ✓ • VİOP History ✓",
                healthOk = result.healthOk,
                authOk = result.authOk,
                bistSymbolsOk = result.symbolsOk,
                bistQuoteOk = result.quoteOk,
                bistHistoryOk = result.historyOk,
                viopContractsOk = result.viopContractsOk,
                viopQuoteOk = result.viopQuoteOk,
                viopHistoryOk = result.viopHistoryOk,
                bistSymbolCount = result.symbolCount,
                viopContractCount = result.viopContractCount,
                testedAt = System.currentTimeMillis()
            )
        } else {
            ProviderReadinessSnapshot(
                state = ProviderState.PROVIDER_ERROR,
                failureCode = mapFailure(result.failureKind),
                message = result.message,
                healthOk = result.healthOk,
                authOk = result.authOk,
                bistSymbolsOk = result.symbolsOk,
                bistQuoteOk = result.quoteOk,
                bistHistoryOk = result.historyOk,
                viopContractsOk = result.viopContractsOk,
                viopQuoteOk = result.viopQuoteOk,
                viopHistoryOk = result.viopHistoryOk,
                bistSymbolCount = result.symbolCount,
                viopContractCount = result.viopContractCount,
                testedAt = System.currentTimeMillis()
            )
        }
    }

    private fun persist(snapshot: ProviderReadinessSnapshot) {
        settings.lastProviderState = snapshot.state.name
        settings.lastProviderFailureCode = snapshot.failureCode.name
        settings.lastProviderMessage = snapshot.message
        settings.lastBackendHealthAt = snapshot.testedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        settings.lastBackendHealthOk = snapshot.state == ProviderState.PROVIDER_READY || (snapshot.state == ProviderState.PROVIDER_STALE_READY && snapshot.failureCode == ProviderFailureCode.NONE)
    }

    companion object {
        const val READY_TTL_MS: Long = 5 * 60_000L

        fun isValidHttps(raw: String): Boolean = runCatching {
            val uri = URI(raw.trim())
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null &&
                uri.host !in setOf("localhost", "127.0.0.1", "10.0.2.2")
        }.getOrDefault(false)

        private fun mapFailure(kind: BackendPreflightClient.FailureKind): ProviderFailureCode = when (kind) {
            BackendPreflightClient.FailureKind.NONE -> ProviderFailureCode.NONE
            BackendPreflightClient.FailureKind.BACKEND_NOT_CONFIGURED -> ProviderFailureCode.BACKEND_URL_MISSING
            BackendPreflightClient.FailureKind.API_KEY_MISSING -> ProviderFailureCode.API_KEY_MISSING
            BackendPreflightClient.FailureKind.HTTPS_REQUIRED,
            BackendPreflightClient.FailureKind.INVALID_URL -> ProviderFailureCode.INVALID_HTTPS
            BackendPreflightClient.FailureKind.TLS_ERROR -> ProviderFailureCode.TLS_ERROR
            BackendPreflightClient.FailureKind.AUTH_ERROR -> ProviderFailureCode.AUTH_ERROR
            BackendPreflightClient.FailureKind.SYMBOLS_ERROR -> ProviderFailureCode.BIST_SYMBOLS_ERROR
            BackendPreflightClient.FailureKind.QUOTE_ERROR -> ProviderFailureCode.BIST_QUOTE_ERROR
            BackendPreflightClient.FailureKind.HISTORY_ERROR -> ProviderFailureCode.BIST_HISTORY_ERROR
            BackendPreflightClient.FailureKind.VIOP_CONTRACTS_ERROR -> ProviderFailureCode.VIOP_CONTRACTS_ERROR
            BackendPreflightClient.FailureKind.VIOP_QUOTE_ERROR -> ProviderFailureCode.VIOP_QUOTE_ERROR
            BackendPreflightClient.FailureKind.VIOP_HISTORY_ERROR -> ProviderFailureCode.VIOP_HISTORY_ERROR
            BackendPreflightClient.FailureKind.STALE_DATA -> ProviderFailureCode.STALE_DATA
            BackendPreflightClient.FailureKind.EMPTY_DATA -> ProviderFailureCode.EMPTY_DATA
            BackendPreflightClient.FailureKind.NETWORK_TIMEOUT -> ProviderFailureCode.NETWORK_TIMEOUT
            BackendPreflightClient.FailureKind.DNS_ERROR -> ProviderFailureCode.DNS_ERROR
            BackendPreflightClient.FailureKind.RATE_LIMIT -> ProviderFailureCode.RATE_LIMIT
            BackendPreflightClient.FailureKind.HTTP_ERROR,
            BackendPreflightClient.FailureKind.SERVER_ERROR -> ProviderFailureCode.SERVER_ERROR
            else -> ProviderFailureCode.UNKNOWN_ERROR
        }
    }
}
