package tr.borsatakip.v5.data

/**
 * BIST taramasında kullanılan gerçek OHLCV periyodu.
 *
 * 1 GÜN hiçbir zaman 240 dakika olarak temsil edilmez. Kalıcılık ve eski model
 * uyumluluğu için günlük periyot 1440 dakika kimliği taşır fakat veri sağlayıcıya
 * her zaman `1d` intervali ile gönderilir.
 */
class ScanTimeframe private constructor(
    val storedMinutes: Int,
    val label: String,
    val apiInterval: String,
    val isDaily: Boolean,
    val isCustom: Boolean
) {
    val cacheKey: String get() = if (isDaily) "1D" else "${storedMinutes}M"

    companion object {
        const val DAILY_STORED_MINUTES = 1440
        private val QUICK_MINUTES = setOf(1, 3, 5, 10, 15, 30, 60)

        val DAILY = ScanTimeframe(DAILY_STORED_MINUTES, "1 GÜN", "1d", isDaily = true, isCustom = false)

        fun minute(minutes: Int, custom: Boolean = minutes !in QUICK_MINUTES): ScanTimeframe {
            require(minutes in 1..239) { "Dakikalık tarama periyodu 1..239 arasında olmalı; 240 DK desteklenmez." }
            return ScanTimeframe(minutes, "$minutes DK", "${minutes}m", isDaily = false, isCustom = custom)
        }

        fun fromStored(value: Int): ScanTimeframe = when {
            value == DAILY_STORED_MINUTES -> DAILY
            value in 1..239 -> minute(value)
            else -> minute(5, custom = false)
        }

        /** Eski 240 DK ayarı 1 GÜN'e çevrilmez; güvenli varsayılan 5 DK'ya döner. */
        fun normalizeStoredMinutes(value: Int): Int = when {
            value == DAILY_STORED_MINUTES -> DAILY_STORED_MINUTES
            value in 1..239 -> value
            else -> 5
        }

        fun quick(): List<ScanTimeframe> = listOf(1, 3, 5, 10, 15, 30, 60).map { minute(it, custom = false) } + DAILY

        fun displayLabel(storedMinutes: Int): String = fromStored(storedMinutes).label

        fun isSupportedStored(value: Int): Boolean = value == DAILY_STORED_MINUTES || value in 1..239
    }
}
