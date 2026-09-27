package tr.borsatakip.v5.data

import okhttp3.Request
import java.net.HttpURLConnection

/**
 * Single backend-auth request surface. The long-lived key remains supported for compatibility,
 * but callers no longer compose auth headers independently. X-Install-ID is not a secret; it
 * provides a stable installation binding for ingress quotas and future short-lived token rollout.
 */
object BackendRequestSecurity {
    fun apply(builder: Request.Builder, settings: SettingsStore): Request.Builder = builder.apply {
        val session = settings.backendSessionToken
        val key = settings.apiKey
        when {
            session.isNotBlank() -> header("Authorization", "BorsaSession $session")
            key.isNotBlank() -> header("Authorization", "Bearer $key")
        }
        header("X-Install-ID", settings.installationId)
    }

    fun apply(connection: HttpURLConnection, settings: SettingsStore) {
        val session = settings.backendSessionToken
        val key = settings.apiKey
        when {
            session.isNotBlank() -> connection.setRequestProperty("Authorization", "BorsaSession $session")
            key.isNotBlank() -> connection.setRequestProperty("Authorization", "Bearer $key")
        }
        connection.setRequestProperty("X-Install-ID", settings.installationId)
    }
}
