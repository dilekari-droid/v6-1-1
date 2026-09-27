package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Candle
import kotlin.math.max

/** BIST ve VİOP için ortak kırılım/hacim anomalisi değerlendirmesi. */
object BreakoutVolumeEngine {
    data class Result(val volumeAnomalyPct:Double?, val state:String, val confirmed:Boolean, val longContribution:Int, val shortContribution:Int)
    fun evaluate(candles:List<Candle>, volumeRatio:Double?, lastPrice:Double?=null, atr:Double?=null, lookback:Int=20, priceBufferRatio:Double=0.0, atrBufferMultiplier:Double=0.0):Result {
        val clean=OhlcvResampler.sanitize(candles)
        val anomaly=volumeRatio?.takeIf{it.isFinite()}?.let{(it-1.0)*100.0}
        if(clean.size<lookback+1) return Result(anomaly,"YOK",false,0,0)
        val prior=clean.dropLast(1).takeLast(lookback)
        val high=prior.maxOf{it.high}; val low=prior.minOf{it.low}; val price=lastPrice?:clean.last().close
        val buffer=max(price*priceBufferRatio,(atr?:0.0)*atrBufferMultiplier)
        val state=when { price>high+buffer -> "YUKARI KIRILIM"; price<low-buffer -> "AŞAĞI KIRILIM"; else -> "YOK" }
        val confirmed=state!="YOK" && (volumeRatio?:0.0)>=1.5
        val lc=when { state=="YUKARI KIRILIM"&&confirmed->8; state=="YUKARI KIRILIM"->3; else->0 }
        val sc=when { state=="AŞAĞI KIRILIM"&&confirmed->8; state=="AŞAĞI KIRILIM"->3; else->0 }
        return Result(anomaly,state,confirmed,lc,sc)
    }
}
