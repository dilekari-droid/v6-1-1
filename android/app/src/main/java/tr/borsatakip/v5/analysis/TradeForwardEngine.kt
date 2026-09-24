package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.RiskPlan
import kotlin.math.abs

/**
 * Stop/target sırasını yalnız giriş zamanından SONRAKİ mumlarda izler.
 *
 * Politika:
 * - target2 yoksa TARGET1 tam çıkış kabul edilir.
 * - target2 varsa TARGET1 bir kilometre taşıdır; pozisyon T2 veya stop gelene kadar izlenir.
 * - TARGET1 sonrasında stop görülürse sıradan STOP yerine TARGET1_THEN_STOP üretilir.
 * - aynı mum içinde stop ve hedef birlikte görülürse intrabar sıra uydurulmaz; AMBIGUOUS döner.
 * - maliyetler verilmezse net P&L uydurulmaz.
 */
object TradeForwardEngine {
    enum class Event { STOP, TARGET1, TARGET1_THEN_STOP, TARGET2, AMBIGUOUS, OPEN, NO_PLAN }

    data class CostConfig(
        val quantity: Double,
        val contractMultiplier: Double = 1.0,
        val commissionPerSide: Double = 0.0,
        val slippageBpsPerSide: Double = 0.0
    ) {
        fun valid() = quantity > 0.0 && contractMultiplier > 0.0 && commissionPerSide >= 0.0 && slippageBpsPerSide >= 0.0
    }

    data class Estimate(
        val direction: String,
        val entry: Double,
        val exit: Double,
        val grossPnl: Double,
        val totalCosts: Double,
        val netPnl: Double,
        val netReturnPct: Double
    )

    /**
     * Ayarlar ekranındaki işlem simülasyonu için mevcut maliyet formülünün doğrudan,
     * emirsiz round-trip hesabı. Ağ/emir katmanına dokunmaz.
     */
    fun estimateRoundTrip(direction: String, entry: Double, exit: Double, costs: CostConfig): kotlin.Result<Estimate> = runCatching {
        require(direction.equals("LONG", true) || direction.equals("SHORT", true)) { "Yön LONG veya SHORT olmalıdır." }
        require(entry.isFinite() && entry > 0.0) { "Giriş fiyatı pozitif olmalıdır." }
        require(exit.isFinite() && exit > 0.0) { "Çıkış fiyatı pozitif olmalıdır." }
        require(costs.valid()) { "Miktar pozitif; komisyon ve slippage negatif olmayan değer olmalıdır." }
        val isLong = direction.equals("LONG", true)
        val signedMove = if (isLong) exit - entry else entry - exit
        val gross = signedMove * costs.quantity * costs.contractMultiplier
        val notionalEntry = abs(entry * costs.quantity * costs.contractMultiplier)
        val notionalExit = abs(exit * costs.quantity * costs.contractMultiplier)
        val slip = (notionalEntry + notionalExit) * (costs.slippageBpsPerSide / 10000.0)
        val commission = costs.commissionPerSide * 2.0
        val total = commission + slip
        val net = gross - total
        val netPct = net / notionalEntry * 100.0
        Estimate(direction.uppercase(), entry, exit, gross, total, net, netPct)
    }

    data class Result(
        val event: Event,
        val eventTime: Long?,
        val eventPrice: Double?,
        val grossReturnPct: Double?,
        val candlesChecked: Int,
        val reason: String,
        val grossPnl: Double? = null,
        val totalCosts: Double? = null,
        val netPnl: Double? = null,
        val netReturnPct: Double? = null,
        val target1Time: Long? = null,
        val mfePct: Double? = null,
        val maePct: Double? = null
    )

    fun evaluate(direction: String, entryTime: Long, plan: RiskPlan?, candles: List<Candle>, costs: CostConfig? = null): Result {
        if (plan == null || plan.stop == null || plan.target1 == null) {
            return Result(Event.NO_PLAN, null, null, null, 0, "Stop/Target planı eksik.")
        }

        // entryTime ile aynı zaman damgasına sahip mum, sinyal üretim/giriş mumu olabilir.
        // Forward test yalnız gerçek gelecek mumları değerlendirir.
        val clean = OhlcvResampler.sanitize(candles).filter { it.timestamp > entryTime }
        if (clean.isEmpty()) return Result(Event.OPEN, null, null, null, 0, "Giriş zamanından sonra doğrulanmış gelecek mum yok.")

        val isLong = direction.equals("LONG", true)
        val target2 = plan.target2
        var target1Time: Long? = null
        var mfePct = 0.0
        var maePct = 0.0

        for ((index, c) in clean.withIndex()) {
            val favorable = if (isLong) (c.high / plan.entry - 1.0) * 100.0 else (plan.entry / c.low - 1.0) * 100.0
            val adverse = if (isLong) (c.low / plan.entry - 1.0) * 100.0 else -((c.high / plan.entry - 1.0) * 100.0)
            mfePct = maxOf(mfePct, favorable)
            maePct = minOf(maePct, adverse)
            val stopHit = if (isLong) c.low <= plan.stop else c.high >= plan.stop
            val t1Hit = if (isLong) c.high >= plan.target1 else c.low <= plan.target1
            val t2Hit = target2 != null && if (isLong) c.high >= target2 else c.low <= target2

            // T1 daha önce gerçekleştiyse T1 seviyesinin sonraki mumda yeniden görülmesi yeni bir
            // belirsizlik yaratmaz. Belirsiz olan yalnız stop ile henüz gerçekleşmemiş hedefin aynı
            // mumda görülmesidir (ilk hedef öncesi T1/T2; T1 sonrası yalnız T2).
            val unresolvedTargetHit = if (target1Time == null) (t1Hit || t2Hit) else t2Hit
            if (stopHit && unresolvedTargetHit) {
                return Result(
                    Event.AMBIGUOUS,
                    c.timestamp,
                    null,
                    null,
                    index + 1,
                    if (target1Time == null) {
                        "Aynı gelecek mumda stop ve hedef aralığı görüldü; intrabar sıra bilinmiyor."
                    } else {
                        "T1 daha önce görüldü; bu mumda stop ve henüz gerçekleşmemiş T2 birlikte görüldü. Intrabar sıra bilinmiyor."
                    },
                    target1Time = target1Time,
                    mfePct = mfePct,
                    maePct = maePct
                )
            }

            if (stopHit) {
                val event = if (target1Time != null) Event.TARGET1_THEN_STOP else Event.STOP
                val reason = if (target1Time != null) {
                    "T1 ${target1Time} zamanında görüldü; ardından stop gerçekleşti. T1, T2 mevcutken kilometre taşı olarak izlendi."
                } else {
                    "Stop, herhangi bir hedef gerçekleşmeden önce görüldü."
                }
                return result(event, c.timestamp, plan.stop, plan.entry, isLong, index + 1, costs, target1Time, reason, mfePct, maePct)
            }

            if (t2Hit) {
                return result(
                    Event.TARGET2,
                    c.timestamp,
                    target2,
                    plan.entry,
                    isLong,
                    index + 1,
                    costs,
                    target1Time,
                    if (target1Time != null) "T1 sonrasında T2 gerçekleşti." else "T2, T1 ile aynı veya önceki doğrulanabilir hedef olayı olarak görüldü.",
                    mfePct,
                    maePct
                )
            }

            if (t1Hit && target1Time == null) {
                target1Time = c.timestamp
                if (target2 == null) {
                    return result(
                        Event.TARGET1,
                        c.timestamp,
                        plan.target1,
                        plan.entry,
                        isLong,
                        index + 1,
                        costs,
                        target1Time,
                        "T2 tanımlı değil; T1 tam çıkış olarak kabul edildi.",
                        mfePct,
                        maePct
                    )
                }
            }
        }

        if (target1Time != null) {
            return result(
                Event.TARGET1,
                target1Time,
                plan.target1,
                plan.entry,
                isLong,
                clean.size,
                costs,
                target1Time,
                "T1 görüldü; T2 veya stop henüz gerçekleşmedi. Sonuç açık pozisyonda T1 kilometre taşı olarak raporlandı.",
                mfePct,
                maePct
            )
        }

        return Result(Event.OPEN, null, null, null, clean.size, "Stop veya hedef henüz gerçekleşmedi.", mfePct = mfePct, maePct = maePct)
    }

    private fun result(
        event: Event,
        time: Long?,
        exit: Double?,
        entry: Double,
        isLong: Boolean,
        checked: Int,
        costs: CostConfig?,
        target1Time: Long?,
        eventReason: String,
        mfePct: Double?,
        maePct: Double?
    ): Result {
        val pct = if (exit != null && entry > 0.0) {
            val raw = (exit / entry - 1.0) * 100.0
            if (isLong) raw else -raw
        } else null

        if (exit == null || costs == null || !costs.valid()) {
            return Result(
                event,
                time,
                exit,
                pct,
                checked,
                "$eventReason • Net P&L için maliyet/miktar yapılandırılmadı.",
                target1Time = target1Time,
                mfePct = mfePct,
                maePct = maePct
            )
        }

        val signedMove = if (isLong) exit - entry else entry - exit
        val gross = signedMove * costs.quantity * costs.contractMultiplier
        val notionalEntry = abs(entry * costs.quantity * costs.contractMultiplier)
        val notionalExit = abs(exit * costs.quantity * costs.contractMultiplier)
        val slip = (notionalEntry + notionalExit) * (costs.slippageBpsPerSide / 10000.0)
        val commission = costs.commissionPerSide * 2.0
        val total = commission + slip
        val net = gross - total
        val netPct = if (notionalEntry > 0.0) net / notionalEntry * 100.0 else null

        return Result(
            event = event,
            eventTime = time,
            eventPrice = exit,
            grossReturnPct = pct,
            candlesChecked = checked,
            reason = "$eventReason • Net P&L maliyetler dahil.",
            grossPnl = gross,
            totalCosts = total,
            netPnl = net,
            netReturnPct = netPct,
            target1Time = target1Time,
            mfePct = mfePct,
            maePct = maePct
        )
    }
}
