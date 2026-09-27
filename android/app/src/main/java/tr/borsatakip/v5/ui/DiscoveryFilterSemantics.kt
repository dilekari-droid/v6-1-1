package tr.borsatakip.v5.ui

/** Pure, presentation-only filter semantics used by Fırsat Kontrolü. */
enum class CoreDiscoveryFilter { ALL, LONG, SHORT, WATCH, STRONG_LONG, STRONG_SHORT }

data class DiscoveryFilterInput(
    val symbol: String,
    val directionLabel: String,
    val strength: Int?
)

object DiscoveryFilterSemantics {
    const val STRONG_THRESHOLD = 81

    fun matches(input: DiscoveryFilterInput, filter: CoreDiscoveryFilter): Boolean {
        val direction = input.directionLabel.trim().uppercase()
        val strength = input.strength
        return when (filter) {
            CoreDiscoveryFilter.ALL -> true
            CoreDiscoveryFilter.LONG -> direction == "LONG"
            CoreDiscoveryFilter.SHORT -> direction == "SHORT"
            CoreDiscoveryFilter.WATCH -> direction == "İZLE" || direction == "WATCH" || direction == "NEUTRAL"
            CoreDiscoveryFilter.STRONG_LONG -> direction == "LONG" && strength != null && strength >= STRONG_THRESHOLD
            CoreDiscoveryFilter.STRONG_SHORT -> direction == "SHORT" && strength != null && strength >= STRONG_THRESHOLD
        }
    }

    fun apply(items: List<DiscoveryFilterInput>, filter: CoreDiscoveryFilter): List<DiscoveryFilterInput> =
        items.filter { matches(it, filter) }
}
