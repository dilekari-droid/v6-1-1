from pathlib import Path
import sys

android = Path(sys.argv[1]).resolve()
root = android / 'app/src/main/java/tr/borsatakip/v5'


def replace_once(path: Path, before: str, after: str, label: str):
    text = path.read_text()
    count = text.count(before)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 occurrence, got {count}')
    path.write_text(text.replace(before, after, 1))

service = root / 'worker/BistScanForegroundService.kt'
provider = root / 'data/MobileMarketDataProvider.kt'

replace_once(
    service,
    'import kotlinx.coroutines.CancellationException\n',
    'import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.delay\n',
    'manual service delay import')

replace_once(
    service,
'''        override fun onAvailable(network: Network) {
            if (::repository.isInitialized) {
                ManualScanPauseGate.resume()
                repository.markNetworkState(true)
                repository.markResumedFromPause()
                ScanRuntimeLog.event(this@BistScanForegroundService, "SCAN_RETRY", "reason=network_recovered")
                updateForeground(repository.snapshot(), force = true)
            }
        }

        override fun onLost(network: Network) {
            if (!hasUsableNetwork() && ::repository.isInitialized) {
                ManualScanPauseGate.pause()
                repository.markNetworkState(false)
                repository.markPaused()
                ScanRuntimeLog.event(this@BistScanForegroundService, "DATA_FETCH_ERROR", "reason=network_lost")
                updateForeground(repository.snapshot(), force = true)
            }
        }''',
'''        override fun onAvailable(network: Network) = applyCurrentNetworkState("network_available")

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            applyCurrentNetworkState("network_capabilities_changed")

        override fun onLost(network: Network) = applyCurrentNetworkState("network_lost")''',
    'manual service network callback')

replace_once(
    service,
'''            if (!hasUsableNetwork()) {
                repository.markUnavailable("Ağ bağlantısı yok. Tarama başlatılmadı; veri/progress uydurulmadı.", "AĞ YOK")
                return
            }

            val productionConfigured''',
'''            awaitValidatedNetworkIfNeeded()

            val productionConfigured''',
    'manual service initial network wait')

anchor = '''    private fun hasUsableNetwork(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
'''
insert = anchor + '''
    private fun applyCurrentNetworkState(reason: String) {
        if (!::repository.isInitialized) return
        val usable = hasUsableNetwork()
        if (usable) {
            val wasPaused = ManualScanPauseGate.isPaused()
            ManualScanPauseGate.resume()
            repository.markNetworkState(true)
            if (wasPaused) {
                repository.markResumedFromPause()
                ScanRuntimeLog.event(this, "SCAN_RETRY", "reason=$reason network_recovered")
            }
        } else {
            ManualScanPauseGate.pause()
            repository.markNetworkState(false)
            repository.markPaused("Ağ bağlantısı yok; tarama bağlantı geri gelene kadar duraklatıldı.")
            ScanRuntimeLog.event(this, "DATA_FETCH_ERROR", "reason=$reason network_unavailable")
        }
        updateForeground(repository.snapshot(), force = true)
    }

    private suspend fun awaitValidatedNetworkIfNeeded() {
        if (hasUsableNetwork()) return
        ManualScanPauseGate.pause()
        repository.markNetworkState(false)
        repository.markPaused("Ağ bağlantısı yok; tarama bağlantı geri gelene kadar duraklatıldı.")
        ScanRuntimeLog.event(this, "DATA_FETCH_ERROR", "reason=network_unavailable_before_scan")
        updateForeground(repository.snapshot(), force = true)
        while (!stopRequested && !hasUsableNetwork()) {
            delay(NETWORK_WAIT_POLL_MS)
        }
        if (stopRequested) throw CancellationException("Tarama ağ beklerken kullanıcı tarafından durduruldu.")
        ManualScanPauseGate.resume()
        repository.markNetworkState(true)
        repository.markResumedFromPause()
        ScanRuntimeLog.event(this, "SCAN_RETRY", "reason=network_recovered_before_scan")
        updateForeground(repository.snapshot(), force = true)
    }
'''
replace_once(service, anchor, insert, 'manual service network helpers')

replace_once(
    service,
    '        private const val MAX_WAKE_LOCK_MS = 2L * 60 * 60 * 1000\n',
    '        private const val MAX_WAKE_LOCK_MS = 2L * 60 * 60 * 1000\n        private const val NETWORK_WAIT_POLL_MS = 500L\n',
    'manual service poll constant')

replace_once(
    provider,
'''        for (attempt in 1..MAX_BATCH_ATTEMPTS) {
            try {
                return withTimeoutOrNull(outerTimeoutMs) {''',
'''        for (attempt in 1..MAX_BATCH_ATTEMPTS) {
            try {
                ManualScanPauseGate.awaitIfPaused()
                return withTimeoutOrNull(outerTimeoutMs) {''',
    'provider pause before retry')

replace_once(
    provider,
'''        private val FATAL_FAILURES = setOf(
            ProviderFailureCode.AUTH_ERROR, ProviderFailureCode.TLS_ERROR, ProviderFailureCode.DNS_ERROR,
            ProviderFailureCode.BACKEND_URL_MISSING, ProviderFailureCode.API_KEY_MISSING, ProviderFailureCode.INVALID_HTTPS
        )
        private val RETRYABLE_FAILURES = setOf(
            ProviderFailureCode.NETWORK_TIMEOUT, ProviderFailureCode.RATE_LIMIT,
            ProviderFailureCode.SERVER_ERROR, ProviderFailureCode.NETWORK_ERROR
        )''',
'''        private val FATAL_FAILURES = setOf(
            ProviderFailureCode.AUTH_ERROR, ProviderFailureCode.TLS_ERROR,
            ProviderFailureCode.BACKEND_URL_MISSING, ProviderFailureCode.API_KEY_MISSING, ProviderFailureCode.INVALID_HTTPS
        )
        private val RETRYABLE_FAILURES = setOf(
            ProviderFailureCode.NETWORK_TIMEOUT, ProviderFailureCode.DNS_ERROR, ProviderFailureCode.RATE_LIMIT,
            ProviderFailureCode.SERVER_ERROR, ProviderFailureCode.NETWORK_ERROR
        )''',
    'provider DNS retry policy')

print('RUNTIME_NETWORK_RESILIENCE_APPLIED')
