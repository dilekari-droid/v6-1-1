package tr.borsatakip.v5.data

import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopContractType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Provider henüz yapılandırılmadığında yalnızca arama/takip listesi için kullanılan
 * fiyat içermeyen yerel sözleşme kataloğu. Bu kayıtlar piyasa verisi veya al/sat
 * sinyali değildir; provider bağlandığında aynı sembollü gerçek kayıtlar önceliklidir.
 */
object ViopBuiltinContractCatalog {
    private val bist30Underlyings = listOf(
        "AKBNK", "GARAN", "ASELS", "THYAO", "TUPRS", "ISCTR", "YKBNK", "KCHOL",
        "SAHOL", "EREGL", "SISE", "PGSUS", "BIMAS", "FROTO", "TOASO", "PETKM", "XU030"
    )

    // Provider yokken yalnız arama/takip listesi için kullanılan fiyat içermeyen kökler.
    // Gerçek piyasa fiyatı veya doğrulanmış aktiflik iddiası taşımazlar.
    private val fxUnderlyings = listOf("USDTRY", "EURTRY")
    private val commodityUnderlyings = listOf("GOLD", "SILVER", "BRENT")

    private val searchableUnderlyings = bist30Underlyings + fxUnderlyings + commodityUnderlyings

    private val zone: ZoneId = ZoneId.of("Europe/Istanbul")

    fun current(nowMs: Long = System.currentTimeMillis()): List<ViopContract> {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val activeMonths = generateSequence(YearMonth.from(now)) { it.plusMonths(1) }
            .take(5)
            .map { month -> month to expiryAt(month) }
            .filter { (_, expiryAt) -> expiryAt >= nowMs }
            .take(3)
            .toList()

        return searchableUnderlyings.flatMap { underlying ->
            activeMonths.map { (month, expiryAt) ->
                ViopContract(
                    symbol = "UNDERLYING:${underlying}:${month.year}-${month.monthValue.toString().padStart(2, '0')}",
                    underlying = underlying,
                    expiry = "%04d-%02d".format(month.year, month.monthValue),
                    contractType = ViopContractType.FUTURE,
                    providerId = "builtin_catalog",
                    providerLabel = "Yerel VİOP Kataloğu",
                    isManual = false,
                    status = "Dayanak referansı • gerçek VİOP kontratı değildir",
                    dataTimestamp = 0L,
                    isRealtime = false,
                    delaySeconds = null,
                    currentSessionIncluded = false,
                    receivedAt = 0L,
                    dataMode = DataMode.UNVERIFIED,
                    validity = SignalValidity.WATCH,
                    validityReason = "Yerel kayıt yalnızca dayanak/vade referansıdır; gerçek VİOP kontrat kodu, fiyatı ve piyasa verisi içermez.",
                    expiryAt = expiryAt,
                    exchangeTimezone = zone.id
                )
            }
        }
    }

    private fun expiryAt(month: YearMonth): Long {
        var date = month.atEndOfMonth()
        while (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            date = date.minusDays(1)
        }
        return ZonedDateTime.of(date, java.time.LocalTime.of(18, 10), zone).toInstant().toEpochMilli()
    }
}
