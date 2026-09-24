package tr.borsatakip.v5.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import tr.borsatakip.v5.data.ProviderFallbackPolicy
import tr.borsatakip.v5.data.ProviderReadinessService
import tr.borsatakip.v5.data.SettingsStore

/** Single configuration + permission + service-start gate shared by every manual BIST scan entry point. */
object BistScanStartGate {
    enum class Outcome { STARTED, PERMISSION_REQUESTED, PROVIDER_UNAVAILABLE, FAILED }

    internal fun requiresNotificationPermission(sdkInt: Int, granted: Boolean): Boolean = sdkInt >= 33 && !granted

    internal fun providerConfigured(
        backendUrlValid: Boolean,
        apiKeyPresent: Boolean,
        fallbackAllowed: Boolean
    ): Boolean = (backendUrlValid && apiKeyPresent) || fallbackAllowed

    private fun providerConfigured(context: Context): Boolean {
        val settings = SettingsStore(context.applicationContext)
        return providerConfigured(
            backendUrlValid = ProviderReadinessService.isValidHttps(settings.baseUrl),
            apiKeyPresent = settings.apiKey.isNotBlank(),
            fallbackAllowed = ProviderFallbackPolicy.allowed(settings.experimentalProvidersEnabled, settings.yahooFallbackEnabled)
        )
    }

    fun startWithPermissionGate(context: Context, requestPermission: () -> Unit): Outcome {
        if (!providerConfigured(context)) return Outcome.PROVIDER_UNAVAILABLE
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (requiresNotificationPermission(Build.VERSION.SDK_INT, granted)) {
            requestPermission()
            return Outcome.PERMISSION_REQUESTED
        }
        return if (BistScanForegroundService.start(context)) Outcome.STARTED else Outcome.FAILED
    }

    fun startAfterPermission(context: Context, granted: Boolean): Outcome {
        if (!granted) return Outcome.FAILED
        if (!providerConfigured(context)) return Outcome.PROVIDER_UNAVAILABLE
        return if (BistScanForegroundService.start(context)) Outcome.STARTED else Outcome.FAILED
    }
}
