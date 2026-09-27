package tr.borsatakip.v5.analysis

/**
 * Resolves only genuine liquidity observations for VIOP.
 *
 * Open interest is intentionally optional: providers that do not expose point-in-time OI
 * must not be replaced with a fabricated value. The confidence engine receives
 * openInterestAvailable=false and can penalise the missing evidence. Volume, however, is a
 * required liquidity observation and may fall back to the latest verified closed OHLCV bar.
 */
object ViopLiquidityInputPolicy {
    enum class VolumeSource { QUOTE, CONTRACT, LAST_CLOSED_BAR, NONE }

    data class Resolution(
        val volume: Double?,
        val volumeSource: VolumeSource,
        val openInterest: Long?,
        val openInterestAvailable: Boolean,
        val rejectCode: String? = null
    )

    fun resolve(
        quoteVolume: Double?,
        contractVolume: Double?,
        lastClosedBarVolume: Double?,
        quoteOpenInterest: Long?,
        contractOpenInterest: Long?,
        minVolume: Double,
        minOpenInterest: Long
    ): Resolution {
        val candidates = listOf(
            VolumeSource.QUOTE to quoteVolume,
            VolumeSource.CONTRACT to contractVolume,
            VolumeSource.LAST_CLOSED_BAR to lastClosedBarVolume
        )
        val chosen = candidates.firstOrNull { (_, value) ->
            value != null && value.isFinite() && value >= minVolume
        }
        if (chosen == null) {
            return Resolution(
                volume = null,
                volumeSource = VolumeSource.NONE,
                openInterest = null,
                openInterestAvailable = false,
                rejectCode = "LOW_LIQUIDITY"
            )
        }

        val oi = sequenceOf(quoteOpenInterest, contractOpenInterest)
            .filterNotNull()
            .firstOrNull { it >= minOpenInterest }
        return Resolution(
            volume = chosen.second,
            volumeSource = chosen.first,
            openInterest = oi,
            openInterestAvailable = oi != null,
            rejectCode = null
        )
    }
}
