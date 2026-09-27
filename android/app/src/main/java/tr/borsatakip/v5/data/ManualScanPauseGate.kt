package tr.borsatakip.v5.data

import kotlinx.coroutines.delay

/**
 * Manuel BIST taramasının ağ kaybında yeni provider işi başlatmasını engeller.
 * PAUSED yalnız gerçek ağ kaybında üretilir; sahte ilerleme üretmez.
 */
object ManualScanPauseGate {
    @Volatile private var paused: Boolean = false

    fun pause() { paused = true }
    fun resume() { paused = false }
    fun isPaused(): Boolean = paused

    suspend fun awaitIfPaused(pollMs: Long = 200L) {
        while (paused) delay(pollMs.coerceIn(50L, 1_000L))
    }
}
