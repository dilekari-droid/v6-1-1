package tr.borsatakip.v5.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tr.borsatakip.v5.data.DynamicMarketScanOrchestrator
import tr.borsatakip.v5.data.DynamicMarketScanResultRepository
import tr.borsatakip.v5.analysis.HomeOpportunityFeedPolicy
import tr.borsatakip.v5.analysis.HomeOpportunityFilterPolicy
import tr.borsatakip.v5.analysis.HomeOpportunitySnapshotStore
import tr.borsatakip.v5.data.ManualScanResultState
import tr.borsatakip.v5.data.DynamicMarketScannerClient
import tr.borsatakip.v5.data.DynamicMultiScanResult
import tr.borsatakip.v5.data.DynamicMultiScanStatus
import tr.borsatakip.v5.data.DynamicResultFilter
import tr.borsatakip.v5.data.MarketCapabilityClient
import tr.borsatakip.v5.data.MarketScanReadinessPolicy
import tr.borsatakip.v5.data.ScanTimeframe
import tr.borsatakip.v5.data.SettingsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DynamicMarketScanActivity : BaseActivity() {
    private enum class MarketTab(val label:String,val capabilityKey:String?) {
        ALL("TÜMÜ",null), BIST("BIST","BIST"), VIOP("VİOP","VIOP"),
        GOLD("ALTIN","COMMODITY"), SILVER("GÜMÜŞ","COMMODITY"),
        COMMODITY("EMTİA","COMMODITY"), FX("DÖVİZ","FX"), INDEX("ENDEKS","INDEX")
    }
    private enum class SignalTab(val label:String) { ALL("TÜMÜ"), LONG("LONG"), SHORT("SHORT"), WATCH("WATCH"), INVALID("INVALID") }

    private lateinit var capabilityText:TextView
    private lateinit var summaryText:TextView
    private lateinit var progressText:TextView
    private lateinit var progressBar:ProgressBar
    private lateinit var scanButton:TextView
    private lateinit var list:ListView
    private lateinit var adapter:ArrayAdapter<String>
    private val marketButtons=linkedMapOf<MarketTab,TextView>()
    private val signalButtons=linkedMapOf<SignalTab,TextView>()
    private var selectedMarket=MarketTab.BIST
    private var selectedSignal=SignalTab.ALL
    private var capabilities:MarketCapabilityClient.Snapshot?=null
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
        header.addView(text("V6.1.1",11,true).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(dp(76),dp(36)))
        root.addView(header)

        capabilityText=text("Provider durumu alınıyor…",12,true).apply { setPadding(dp(12),dp(10),dp(12),dp(10)); background=box(Color.rgb(8,42,64),Color.rgb(28,118,170)) }
        root.addView(capabilityText,LinearLayout.LayoutParams(MATCH,WRAP).apply { bottomMargin=dp(8) })
        root.addView(text("VARLIK SINIFI",10,true).apply { setTextColor(Color.rgb(140,180,205)) })
        root.addView(marketStrip(),LinearLayout.LayoutParams(MATCH,dp(48)))
        root.addView(text("SONUÇ FİLTRESİ",10,true).apply { setTextColor(Color.rgb(140,180,205)); setPadding(0,dp(5),0,0) })
        root.addView(signalStrip(),LinearLayout.LayoutParams(MATCH,dp(48)))

        summaryText=text("BIST seçili • capability doğrulaması bekleniyor",11,false).apply { setPadding(dp(10),dp(9),dp(10),dp(9)); background=box(Color.rgb(6,34,52),Color.rgb(34,74,98)) }
        root.addView(summaryText,LinearLayout.LayoutParams(MATCH,WRAP).apply { topMargin=dp(6); bottomMargin=dp(5) })

        progressText=text("TARAMA İLERLEMESİ • bekliyor",10,true).apply { setTextColor(Color.rgb(140,180,205)); setPadding(dp(4),dp(2),dp(4),dp(2)) }
        root.addView(progressText,LinearLayout.LayoutParams(MATCH,WRAP))
        progressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progress=0; isIndeterminate=false }
        root.addView(progressBar,LinearLayout.LayoutParams(MATCH,dp(8)).apply { bottomMargin=dp(7) })

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
            b.setOnClickListener { selectedMarket=tab; DynamicMarketScanResultRepository.clear(); adapter.clear(); adapter.notifyDataSetChanged(); updateMarketButtons(); describeSelection() }
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
            capabilityText.text="Piyasa bazlı provider durumu kontrol ediliyor…"
            MarketCapabilityClient(this@DynamicMarketScanActivity).load().fold(
                onSuccess={ snapshot ->
                    capabilities=snapshot
                    capabilityText.text=ProviderCapabilityPresentationPolicy.globalStatus(snapshot)
                    updateMarketButtons(); describeSelection()
                },
                onFailure={ e -> capabilityText.text="! PROVIDER KONTROL HATASI • ${e.message ?: e.javaClass.simpleName}" }
            )
        }
    }

    private fun startScan() {
        if(scanJob?.isActive==true) {
            scanJob?.cancel()
            scanButton.text="▶ TARAMAYI BAŞLAT"
            progressText.text="TARAMA İLERLEMESİ • durduruldu"
            Log.i(LOG_TAG,"SCAN_CANCEL selection=${selectedMarket.name}")
            return
        }
        val snapshot=capabilities
        if(snapshot==null) { Toast.makeText(this,"Önce provider kontrolü tamamlanmalı.",Toast.LENGTH_SHORT).show(); refreshCapabilities(); return }

        val plan=MarketScanReadinessPolicy.plan(selectedMarket.name,snapshot)
        if(!plan.canStart) {
            summaryText.text=unavailableSummary(selectedMarket.label,plan.unavailableRoutes.map { "${it.route.label}: ${it.reason ?: "MARKET_SCAN_NOT_READY"}" })
            progressBar.progress=0
            progressText.text="TARAMA İLERLEMESİ • başlatılamadı"
            return
        }

        scanJob=lifecycleScope.launch {
            val scanStartedAt=System.currentTimeMillis()
            scanButton.text="■ DURDUR"
            progressBar.progress=0
            progressText.text="TARAMA İLERLEMESİ • %0"
            val tf=ScanTimeframe.fromStored(SettingsStore(this@DynamicMarketScanActivity).analysisTimeframeMinutes)
            if (tf.isCustom) {
                summaryText.text="Dinamik tarama ${tf.label} desteklemiyor • 1/3/5/10/15/30/60 DK veya 1 GÜN seçin. Özel timeframe klasik BIST taramasında kullanılabilir."
                Toast.makeText(this@DynamicMarketScanActivity,"Özel timeframe dinamik taramada kapalı; kanonik periyot seçin.",Toast.LENGTH_LONG).show()
                scanButton.text="▶ TARAMAYI BAŞLAT"
                progressText.text="TARAMA İLERLEMESİ • başlatılamadı"
                return@launch
            }

            val readyLabels=plan.readyRoutes.joinToString("/") { it.label }
            summaryText.text="$readyLabels taranıyor • ${tf.label} • Queue + Batch + Rate Limit"
            Log.i(LOG_TAG,"SCAN_START provider=${snapshot.primaryProvider} selection=${selectedMarket.name} ready=${plan.readyRoutes.joinToString(",") { it.id }} timeframe=${tf.apiInterval} startedAt=$scanStartedAt")
            val client=DynamicMarketScannerClient(this@DynamicMarketScanActivity)
            val orchestrator=DynamicMarketScanOrchestrator { route,timeframe,minScore,includeWatch ->
                withContext(Dispatchers.Main.immediate) {
                    progressBar.progress=0
                    progressText.text="${route.label} • %0 • 0/? işlendi"
                }
                client.loadAll(route.backendMarket,route.assetType,timeframe,minScore,includeWatch) { progress ->
                    withContext(Dispatchers.Main.immediate) {
                        progressBar.progress=progress.percent
                        progressText.text="${route.label} • %${progress.percent} • ${progress.scannedSymbols}/${progress.universeCount} işlendi • ${progress.successfulCount} başarılı • ${progress.failedCount} hata"
                    }
                    Log.d(LOG_TAG,"SCAN_PROGRESS market=${route.id} percent=${progress.percent} scanned=${progress.scannedSymbols}/${progress.universeCount} success=${progress.successfulCount} failed=${progress.failedCount}")
                }
            }
            val result=orchestrator.run(snapshot,selectedMarket.name,tf.apiInterval,0,true)
            DynamicMarketScanResultRepository.publish(result)
            val recoveryFeed = HomeOpportunityFeedPolicy.resolve(
                dynamic = DynamicMarketScanResultRepository.snapshot(),
                manual = ManualScanResultState(),
                selected = HomeOpportunityFilterPolicy.Filter.ALL
            )
            HomeOpportunitySnapshotStore(this@DynamicMarketScanActivity).save(recoveryFeed)
            summaryText.text=scanSummary(result,tf.label)
            val processedComplete=result.universeCount>0 && result.scannedSymbols>=result.universeCount
            progressBar.progress=if(processedComplete) 100 else progressBar.progress
            progressText.text=if(result.universeCount>0) {
                val pct=((result.scannedSymbols.toLong()*100L)/result.universeCount.toLong()).toInt().coerceIn(0,100)
                "TARAMA İLERLEMESİ • %$pct • ${result.scannedSymbols}/${result.universeCount} işlendi • ${result.successfulCount} başarılı • ${result.failedCount} hata"
            } else {
                "TARAMA İLERLEMESİ • veri evreni doğrulanamadı"
            }
            renderResults()
            scanButton.text="▶ TARAMAYI BAŞLAT"
            val endedAt=System.currentTimeMillis()
            Log.i(LOG_TAG,"SCAN_END provider=${snapshot.primaryProvider} selection=${selectedMarket.name} status=${result.status} total=${result.universeCount} scanned=${result.scannedSymbols} success=${result.successfulCount} failed=${result.failedCount} startedAt=$scanStartedAt endedAt=$endedAt durationMs=${endedAt-scanStartedAt}")
        }
    }

    private fun renderResults() {
        val result=DynamicMarketScanResultRepository.snapshot().result ?: return
        val rows=mutableListOf<String>()
        val filter=DynamicResultFilter.valueOf(selectedSignal.name)

        if(selectedSignal==SignalTab.ALL) {
            result.skipped.forEach { skipped ->
                rows += "ATLANDI • ${skipped.route.label}\n${skipped.reason ?: "MARKET_SCAN_NOT_READY"}"
            }
            result.failed.forEach { failed ->
                rows += "HATA • ${failed.route.label}\n${failed.message}"
            }
        }

        if(selectedSignal==SignalTab.INVALID) {
            DynamicMarketScanResultRepository.invalidFailures().forEach { f ->
                rows += "INVALID • ${f.market ?: "?"} • ${f.symbol ?: "—"}\n${f.code} • ${f.message}"
            }
        } else {
            DynamicMarketScanResultRepository.filteredItems(filter).forEach { x ->
                val price=x.price?.let { "%.2f".format(it) } ?: "—"
                val change=x.dailyChangePct?.let { "%+.2f%%".format(it) } ?: "—"
                val rsi=x.rsi14?.let { "%.1f".format(it) } ?: "—"
                val delay=x.delaySeconds?.let { "${it}s" } ?: "?"
                val time=x.timestamp?.let { SimpleDateFormat("dd.MM HH:mm:ss",Locale("tr","TR")).format(Date(it)) } ?: "?"
                val shownSignal=DynamicMarketScanResultRepository.canonicalDirection(x)
                rows += "$shownSignal • ${x.symbol} • ${x.market}/${x.assetType}\nFiyat $price • $change • Skor ${x.score} • Güven ${x.confidence}/100 (${x.confidenceBand})\nRSI $rsi • ${x.verificationStatus} • ${x.publicationMode} • ${x.analysisTimeframe} • ${if(x.realtime) "CANLI" else "GECİKMELİ"} ($delay) • $time\n${x.source}"
            }
        }
        if(rows.isEmpty()) rows += "Bu filtre için sonuç yok."
        adapter.clear(); adapter.addAll(rows); adapter.notifyDataSetChanged()
    }

    private fun describeSelection() {
        val snapshot=capabilities ?: return
        val plan=MarketScanReadinessPolicy.plan(selectedMarket.name,snapshot)
        if(selectedMarket==MarketTab.ALL) {
            summaryText.text=when {
                !plan.canStart -> unavailableSummary("TÜM PİYASALAR",plan.unavailableRoutes.map { it.route.label })
                plan.isPartial -> "KISMİ TARAMA • ${plan.readyRoutes.joinToString("/") { it.label }} hazır • ${plan.unavailableRoutes.joinToString("/") { it.route.label }} kullanılamıyor"
                else -> "TÜMÜ hazır • ${plan.readyRoutes.joinToString("/") { it.label }}"
            }
            return
        }
        val key=selectedMarket.capabilityKey ?: return
        summaryText.text=ProviderCapabilityPresentationPolicy.marketSummary(selectedMarket.label,key,snapshot)
    }

    private fun scanSummary(result:DynamicMultiScanResult,timeframeLabel:String):String {
        val skipped=result.skipped.joinToString("/") { it.route.label }.ifBlank { "yok" }
        val failed=result.failed.joinToString("/") { it.route.label }.ifBlank { "yok" }
        val completed=result.completed.joinToString("/") { it.route.label }.ifBlank { "yok" }
        val status=when(result.status) {
            DynamicMultiScanStatus.COMPLETED -> "TAM"
            DynamicMultiScanStatus.PARTIAL -> "KISMİ"
            DynamicMultiScanStatus.UNAVAILABLE -> "KULLANILAMIYOR"
            DynamicMultiScanStatus.ERROR -> "HATA"
        }
        return "TARAMA TAMAMLANDI • Durum: $status • $timeframeLabel • Tamamlanan: $completed • Atlanan: $skipped • Hata: $failed • ${result.scannedSymbols}/${result.universeCount} işlendi • ${result.successfulCount} başarılı • ${result.failedCount} hata"
    }

    private fun unavailableSummary(label:String,reasons:List<String>):String =
        "$label kullanılamıyor • ${reasons.joinToString(" • ").ifBlank { "MARKET_SCAN_NOT_READY" }}"

    private fun updateMarketButtons() { marketButtons.forEach { (k,v) -> styleChip(v,k==selectedMarket, marketAvailable(k)) } }
    private fun updateSignalButtons() { signalButtons.forEach { (k,v) -> styleChip(v,k==selectedSignal,true) } }
    private fun marketAvailable(tab:MarketTab):Boolean {
        val snapshot=capabilities ?: return tab==MarketTab.BIST
        return MarketScanReadinessPolicy.plan(tab.name,snapshot).canStart
    }
    private fun styleChip(v:TextView, selected:Boolean, available:Boolean) {
        v.background=box(if(selected) Color.rgb(0,95,170) else Color.rgb(8,41,62), if(selected) Color.rgb(48,171,255) else Color.rgb(40,83,108))
        v.setTextColor(if(available) Color.WHITE else Color.rgb(135,150,160)); v.alpha=if(available||selected) 1f else .62f
    }
    private fun chip(label:String)=text(label,11,true).apply { gravity=Gravity.CENTER; setPadding(dp(14),0,dp(14),0) }
    private fun text(value:String,size:Int,bold:Boolean)=TextView(this).apply { text=value; textSize=size.toFloat(); setTextColor(Color.WHITE); if(bold)setTypeface(typeface,Typeface.BOLD) }
    private fun box(fill:Int,stroke:Int)=GradientDrawable().apply { shape=GradientDrawable.RECTANGLE; cornerRadius=dp(10).toFloat(); setColor(fill); setStroke(dp(1),stroke) }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    companion object { private const val MATCH=-1; private const val WRAP=-2; private const val LOG_TAG="DynamicMarketScan" }
}
