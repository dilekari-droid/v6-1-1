package tr.borsatakip.v5.analysis

enum class SignalIntensity { VERY_LOW, LOW, MEDIUM, HIGH, MAXIMUM }

data class SignalVisualStyle(
    val direction: String,
    val quality: Int,
    val intensity: SignalIntensity,
    val red: Int,
    val green: Int,
    val blue: Int,
    val arrow: String
)

object SignalVisualPolicy {
    fun qualityFor(direction: String, longScore: Int, shortScore: Int): Int? = when {
        direction.equals("LONG", ignoreCase = true) -> longScore.coerceIn(0, 100)
        direction.equals("SHORT", ignoreCase = true) -> shortScore.coerceIn(0, 100)
        else -> null
    }

    fun resolve(direction: String, longScore: Int, shortScore: Int): SignalVisualStyle? {
        val quality = qualityFor(direction, longScore, shortScore) ?: return null
        val intensity = when (quality) {
            in 0..20 -> SignalIntensity.VERY_LOW
            in 21..40 -> SignalIntensity.LOW
            in 41..60 -> SignalIntensity.MEDIUM
            in 61..80 -> SignalIntensity.HIGH
            else -> SignalIntensity.MAXIMUM
        }
        val long = direction.equals("LONG", ignoreCase = true)
        // Rapordaki YÖN=RENK / GÜÇ=PARLAKLIK kuralı. En güçlü tonlar tam palette ulaşır.
        val rgb = if (long) {
            when (intensity) {
                SignalIntensity.VERY_LOW -> intArrayOf(29, 78, 59)
                SignalIntensity.LOW -> intArrayOf(25, 110, 70)
                SignalIntensity.MEDIUM -> intArrayOf(18, 151, 75)
                SignalIntensity.HIGH -> intArrayOf(7, 199, 102)
                SignalIntensity.MAXIMUM -> intArrayOf(0, 255, 136) // #00FF88
            }
        } else {
            when (intensity) {
                SignalIntensity.VERY_LOW -> intArrayOf(101, 46, 52)
                SignalIntensity.LOW -> intArrayOf(139, 49, 58)
                SignalIntensity.MEDIUM -> intArrayOf(188, 48, 61)
                SignalIntensity.HIGH -> intArrayOf(235, 43, 62)
                SignalIntensity.MAXIMUM -> intArrayOf(255, 23, 68) // #FF1744
            }
        }
        return SignalVisualStyle(
            direction = if (long) "LONG" else "SHORT",
            quality = quality,
            intensity = intensity,
            red = rgb[0], green = rgb[1], blue = rgb[2],
            arrow = if (long) "▲" else "▼"
        )
    }
}
