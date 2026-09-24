package tr.borsatakip.v5.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tr.borsatakip.v5.data.DynamicMarketScannerClient
import tr.borsatakip.v5.data.MarketCapabilityClient
import tr.borsatakip.v5.data.ScanTimeframe
import tr.borsatakip.v5.data.SettingsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DynamicMarketScanActivity : BaseActivity() {
    private enum class MarketTab(val label:String,val backendMarket:String,val assetType:String,val capabilityKey:String?) {
        ALL("TÜMÜ","ALL","ALL",null), BIST("BIST","BIST","STOCK","BIST"), VIOP("VİOP","VIOP","FUTURE","VIOP"),
        GOLD("ALTIN","COMMODITY","COMMODITY","COMMODITY"), SILVER("GÜMÜŞ","COMMODITY","COMMODITY","COMMODITY"),
        COMMODITY("EMTİA","COMMODITY","COMMODITY","COMMODITY"), FX("DÖVİZ","FX","FX","FX"), INDEX("ENDEKS","INDEX","INDEX","INDEX")
    }
    private enum class SignalTab(val label:String) { ALL("TÜMÜ"), LONG("LONG"), SHORT("SHORT"), WATCH("WATCH"), INVALID("INVALID") }

    private lateinit var capabilityText:TextView
    private lateinit var summaryText:TextView
    private lateinit var scanButton:TextView
    private lateinit var list:ListView
    private lateinit var adapter:ArrayAdapter<String>
    private val marketButtons=linkedMapOf<MarketTab,TextView>()
    private val signalButtons=linkedMapOf<SignalTab,TextView>()
    private var selectedMarket=MarketTab.BIST
    private var selectedSignal=SignalTab.ALL
    private var capabilities:MarketCapabilityClient.Snapshot?=null
    private var lastResponse:DynamicMarketScannerClient.Response?=null
    private var scanJob:Job?=null

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refreshCapabilities()
    }

    private fun buildUi():View {
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(4,22,37)); setPadding(dp(12),dp(10),dp(12),dp(10)) }
        val header=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        header.addView(text("‹",28,true).apply { setPadding(dp(8),0,dp(18),0); setOnClickListener { finish() } }, LinearLayout.LayoutParams(WRAP,dp(48)))
        header.addView(text("PİYASA TARAMA",20,true),LinearLayout.LayoutParams(0,dp(48),1f))
        header.addView(text("V5.4.16",11,true).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(dp(76),dp(36)))
        root.addView(header)

        capabilityText=text("Provider durumu alınıyor…",12,true).apply { setPadding(dp(12),dp(10),dp(12),dp(10)); background=box(Color.rgb(8,42,64),Color.rgb(28,118,170)) }
        root.addView(capabilityText,LinearLayout.LayoutParams(MATCH,WRAP).apply { bottomMargin=dp(8) })
        root.addView(text("VARLIK SINIFI",10,true).apply { setTextColor(Color.rgb(140,180,205)) })
        root.addView(marketStrip(),LinearLayout.LayoutParams(MATCH,dp(48)))
        root.addView(text("SONUÇ FİLTRESİ",10,true).apply { setTextColor(Color.rgb(140,180,205)); setPadding(0,dp(5),0,0) })
        root.addView(signalStrip(),LinearLayout.LayoutParams(MATCH,dp(48)))

        summaryText=text("BIST seçili • capability doğrulaması bekleniyor",11,false).apply { setPadding(dp(10),dp(9),dp(10),dp(9)); background=box(Color.rgb(6,34,52),Color.rgb(34,74,98)) }
        root.addView(summaryText,LinearLayout.LayoutParams(MATCH,WRAP).apply { topMargin=dp(6); bottomMargin=dp(7) })

        scanButton=text("▶ TARAMAYI BAŞLAT",14,true).apply { gravity=Gravity.CENTER; background=box(Color.rgb(0,112,214),Color.rgb(37,157,255)); setOnClickListener { startScan() } }
        root.addView(scanButton,LinearLayout.LayoutParams(MATCH,dp(48)).apply { bottomMargin=dp(8) })

        adapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,mutableListOf<String>())
        list=ListView(this).apply { setBackgroundColor(Color.TRANSPARENT); dividerHeight=dp(6); adapter=this@DynamicMarketScanActivity.adapter }
        root.addView(list,LinearLayout.LayoutParams(MATCH,0,1f))
        return root
    }

    private fun marketStrip():View {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        MarketTab.entries.forEach { tab ->
            val b=chip(tab.label)
            marketButtons[tab]=b
            b.setOnClickListener { selectedMarket=tab; lastResponse=null; adapter.clear(); adapter.notifyDataSetChanged(); updateMarketButtons(); describeSelection() }
            row.addView(b,LinearLayout.LayoutParams(WRAP,dp(38)).apply { marginEnd=dp(6) })
        }
        updateMarketButtons()
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(row) }
    }

    private fun signalStrip():View {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        SignalTab.entries.forEach { tab ->
            val b=chip(tab.label)
            signalButtons[tab]=b
            b.setOnClickListener { selectedSignal=tab; updateSignalButtons(); renderResults() }
            row.addView(b,LinearLayout.LayoutParams(WRAP,dp(38)).apply { marginEnd=dp(6) })
        }
        updateSignalButtons()
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(row) }
    }

    private fun refreshCapabilities() {
        lifecycleScope.launch {
            capabilityText.text="Provider zinciri kontrol ediliyor…"
            MarketCapabilityClient(this@DynamicMarketScanActivity).load().fold(
                onSuccess={ snapshot ->
                    capabilities=snapshot
                    val bist=snapshot.market("BIST")
                    capabilityText.text=if(snapshot.providerReady) "✓ PROVIDER HAZIR • ${snapshot.primaryProvider}" else "! GLOBAL PROVIDER HAZIR DEĞİL • BIST ${if(bist?.ready==true) "HAZIR (${bist.symbolCount})" else "HAZIR DEĞİL"} • TradeWize ${snapshot.tradeWizeState}"
                    updateMarketButtons(); describeSelection()
                },
                onFailure={ e -> capabilityText.text="! PROVIDER KONTROL HATASI • ${e.message ?: e.javaClass.simpleName}" }
            )
        }
    }

    private fun startScan() {
        if(scanJob?.isActive==true) { scanJob?.cancel(); scanButton.text="▶ TARAMAYI BAŞLAT"; return }
        val snapshot=capabilities
        if(snapshot==null) { Toast.makeText(this,"Önce provider kontrolü tamamlanmalı.",Toast.LENGTH_SHORT).show(); refreshCapabilities(); return }
        if(selectedMarket==MarketTab.ALL && !snapshot.multiMarketReady) { summaryText.text="TÜM PİYASALAR engellendi • Çoklu piyasa provider zinciri henüz hazır değil. BIST ayrı olarak taranabilir."; return }
        val cap=selectedMarket.capabilityKey?.let { snapshot.market(it) }
        if(cap?.ready!=true && cap?.analysisReady!=true) { summaryText.text="${selectedMarket.label} kullanılamıyor • ${cap?.reasonCode ?: "PROVIDER_NOT_READY"} • ${cap?.message ?: "Dinamik discovery/market data hazır değil."}"; return }
        scanJob=lifecycleScope.launch {
            scanButton.text="■ DURDUR"
            val tf=ScanTimeframe.fromStored(SettingsStore(this@DynamicMarketScanActivity).analysisTimeframeMinutes)
            if (tf.isCustom) {
                summaryText.text="Dinamik tarama ${tf.label} desteklemiyor • 1/3/5/10/15/30/60 DK veya 1 GÜN seçin. Özel timeframe klasik BIST taramasında kullanılabilir."
                Toast.makeText(this@DynamicMarketScanActivity,"Özel timeframe dinamik taramada kapalı; kanonik periyot seçin.",Toast.LENGTH_LONG).show()
                scanButton.text="▶ TARAMAYI BAŞLAT"
                return@launch
            }
            summaryText.text="${selectedMarket.label} taranıyor • ${tf.label} • Queue + Batch + Rate Limit"
            DynamicMarketScannerClient(this@DynamicMarketScanActivity).loadAll(selectedMarket.backendMarket,selectedMarket.assetType,tf.apiInterval,0,true).fold(
                onSuccess={ r -> lastResponse=r; summaryText.text="${selectedMarket.label} • ${r.timeframe} • ${r.analysisMode} • Kapsama ${r.scannedSymbols}/${r.universeCount} • ${if(r.coverageComplete) "TAM" else "EKSİK (${r.remainingSymbols} kaldı)"} • ${r.successfulCount} başarılı • ${r.failedCount} hata • batch ${r.batchSize} / concurrency ${r.concurrency} / pacing ${r.pacingMs}ms • ${r.source}"; renderResults() },
                onFailure={ e -> lastResponse=null; adapter.clear(); adapter.add("INVALID • ${e.message ?: e.javaClass.simpleName}"); adapter.notifyDataSetChanged(); summaryText.text="Tarama başarısız • ${e.message ?: e.javaClass.simpleName}" }
            )
            scanButton.text="▶ TARAMAYI BAŞLAT"
        }
    }

    private fun renderResults() {
        val r=lastResponse ?: return
        val rows=mutableListOf<String>()
        if(selectedSignal==SignalTab.INVALID) {
            r.failures.forEach { f -> rows += "INVALID • ${f.market ?: "?"} • ${f.symbol ?: "—"}\n${f.code} • ${f.message}" }
        } else {
            r.items.filter { selectedSignal==SignalTab.ALL || (if (it.realtime) it.signal else it.technicalSignal).equals(selectedSignal.name,true) }.forEach { x ->
                val price=x.price?.let { "%.2f".format(it) } ?: "—"
                val change=x.dailyChangePct?.let { "%+.2f%%".format(it) } ?: "—"
                val rsi=x.rsi14?.let { "%.1f".format(it) } ?: "—"
                val delay=x.delaySeconds?.let { "${it}s" } ?: "?"
                val time=x.timestamp?.let { SimpleDateFormat("dd.MM HH:mm:ss",Locale("tr","TR")).format(Date(it)) } ?: "?"
                val shownSignal=if(x.realtime) x.signal else x.technicalSignal
                rows += "$shownSignal • ${x.symbol} • ${x.market}/${x.assetType}\nFiyat $price • $change • Skor ${x.score} • Güven ${x.confidence}/100 (${x.confidenceBand})\nRSI $rsi • ${x.verificationStatus} • ${x.publicationMode} • ${x.analysisTimeframe} • ${if(x.realtime) "CANLI" else "GECİKMELİ"} ($delay) • $time\n${x.source}"
            }
        }
        if(rows.isEmpty()) rows += "Bu filtre için sonuç yok."
        adapter.clear(); adapter.addAll(rows); adapter.notifyDataSetChanged()
    }

    private fun describeSelection() {
        val s=capabilities ?: return
        if(selectedMarket==MarketTab.ALL) {
            summaryText.text=if(s.multiMarketReady) "Tüm piyasa evreni hazır." else "TÜMÜ beklemede • BIST hazır olsa da VİOP/emtia/döviz discovery zinciri tamamlanmadı."
            return
        }
        val c=selectedMarket.capabilityKey?.let { s.market(it) }
        summaryText.text=when { c?.ready==true -> "${selectedMarket.label} REALTIME HAZIR • ${c.symbolCount.takeIf { it>0 }?.let { "$it varlık • " } ?: ""}${c.provider ?: s.primaryProvider}"; c?.analysisReady==true -> "${selectedMarket.label} GECİKMELİ ANALİZ HAZIR • ${c.symbolCount.takeIf { it>0 }?.let { "$it varlık • " } ?: ""}${c.message ?: "Canlı quote doğrulanmadı; kapanmış bar analizi kullanılabilir."}"; else -> "${selectedMarket.label} HAZIR DEĞİL • ${c?.reasonCode ?: "PROVIDER_NOT_READY"} • ${c?.message ?: "Dinamik provider desteği yok."}" }
    }

    private fun updateMarketButtons() { marketButtons.forEach { (k,v) -> styleChip(v,k==selectedMarket, marketAvailable(k)) } }
    private fun updateSignalButtons() { signalButtons.forEach { (k,v) -> styleChip(v,k==selectedSignal,true) } }
    private fun marketAvailable(tab:MarketTab):Boolean {
        val s=capabilities ?: return tab==MarketTab.BIST
        return if(tab==MarketTab.ALL) s.multiMarketReady else tab.capabilityKey?.let { key -> s.market(key)?.let { it.ready || it.analysisReady } }==true
    }
    private fun styleChip(v:TextView, selected:Boolean, available:Boolean) {
        v.background=box(if(selected) Color.rgb(0,95,170) else Color.rgb(8,41,62), if(selected) Color.rgb(48,171,255) else Color.rgb(40,83,108))
        v.setTextColor(if(available) Color.WHITE else Color.rgb(135,150,160)); v.alpha=if(available||selected) 1f else .62f
    }
    private fun chip(label:String)=text(label,11,true).apply { gravity=Gravity.CENTER; setPadding(dp(14),0,dp(14),0) }
    private fun text(value:String,size:Int,bold:Boolean)=TextView(this).apply { text=value; textSize=size.toFloat(); setTextColor(Color.WHITE); if(bold)setTypeface(typeface,Typeface.BOLD) }
    private fun box(fill:Int,stroke:Int)=GradientDrawable().apply { shape=GradientDrawable.RECTANGLE; cornerRadius=dp(10).toFloat(); setColor(fill); setStroke(dp(1),stroke) }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    companion object { private const val MATCH=-1; private const val WRAP=-2 }
}
