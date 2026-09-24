package tr.borsatakip.v5.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * BIST kapanış taraması için zaman ve son mum bütünlük politikası.
 *
 * Bu sınıf borsa tatili takvimi uydurmaz. Seans sonrası aynı güne ait kapanmış mum yoksa
 * "bugünkü kapanış" varmış gibi kabul etmez; hangi son işlem gününün elde olduğunu açıkça döndürür.
 */
object BistSessionClosePolicy {
    private val zone: ZoneId = ZoneId.of("Europe/Istanbul")
    private val sessionStart: LocalTime = LocalTime.of(10, 0)
    private val sessionEnd: LocalTime = LocalTime.of(18, 10)

    enum class Phase { BEFORE_OPEN, OPEN, AFTER_CLOSE, WEEKEND }

    data class Verdict(
        val accepted: Boolean,
        val phase: Phase,
        val sessionDate: LocalDate?,
        val reason: String
    )

    fun phase(nowMs: Long = System.currentTimeMillis()): Phase {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return Phase.WEEKEND
        val t = now.toLocalTime()
        return when {
            t < sessionStart -> Phase.BEFORE_OPEN
            t <= sessionEnd -> Phase.OPEN
            else -> Phase.AFTER_CLOSE
        }
    }

    fun localDate(timestampMs: Long): LocalDate? =
        timestampMs.takeIf { it > 0L }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    /**
     * Seans dışı taramada kullanılacak son mumun gerçekten kapanmış ve doğru işlem gününe ait olduğunu doğrular.
     * - Seans sonrası: yalnız aynı günün kapanışı kabul edilir.
     * - Seans öncesi / hafta sonu: son tamamlanmış işlem günü kabul edilir ve tarihi etiketlenir.
     * - Seans açıkken: bu mod kullanılamaz; canlı yol kullanılmalıdır.
     */
    fun validateLastClosedBar(
        lastBarTimestamp: Long,
        frameMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Verdict {
        val p = phase(nowMs)
        val nowDate = localDate(nowMs)
        val barDate = localDate(lastBarTimestamp)
        if (lastBarTimestamp <= 0L || barDate == null) {
            return Verdict(false, p, null, "Kapanış mumu zaman damgası geçersiz.")
        }
        if (frameMs <= 0L || nowMs < lastBarTimestamp + frameMs) {
            return Verdict(false, p, barDate, "Son ${frameMs / 60_000L} DK mumu henüz kapanmadı.")
        }
        if (barDate.isAfter(requireNotNull(nowDate))) {
            return Verdict(false, p, barDate, "Kapanış mumu gelecekte tarihli.")
        }
        return when (p) {
            Phase.OPEN -> Verdict(false, p, barDate, "BIST seansı açık; kapanış modu yerine canlı tarama kullanılmalı.")
            Phase.AFTER_CLOSE -> {
                if (barDate == nowDate) {
                    Verdict(true, p, barDate, "Bugünkü tamamlanmış kapanış verisi doğrulandı.")
                } else {
                    Verdict(false, p, barDate, "Bugünkü kapanış verisi henüz sağlayıcıda yok. Son mevcut işlem günü: $barDate")
                }
            }
            Phase.BEFORE_OPEN, Phase.WEEKEND -> Verdict(
                true,
                p,
                barDate,
                "Son tamamlanmış işlem günü kapanışı kullanılacak: $barDate"
            )
        }
    }
}
