from pathlib import Path
import sys

android = Path(sys.argv[1]).resolve()
root = android / 'app/src/main/java/tr/borsatakip/v5'

def replace_once(rel: str, before: str, after: str, label: str):
    path = root / rel
    text = path.read_text()
    count = text.count(before)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 occurrence, got {count}')
    path.write_text(text.replace(before, after, 1))

# BIST primary UI: terminal provider/scanner errors never expose raw backend/session text.
replace_once(
    'ui/BistScanActivity.kt',
    '        heroSubtitle.text = session.message ?: initialHeroMessage()',
    '''        heroSubtitle.text = when (session.status) {
            ManualScanStatus.DATA_UNAVAILABLE -> "Güncel BIST verisi doğrulanamadı. Veri sağlayıcı durumunu kontrol edin."
            ManualScanStatus.ERROR, ManualScanStatus.INTERRUPTED -> "Tarama tamamlanamadı. Bağlantı ve veri sağlayıcı durumunu kontrol edin."
            else -> session.message ?: initialHeroMessage()
        }
        if (session.status in setOf(ManualScanStatus.DATA_UNAVAILABLE, ManualScanStatus.ERROR, ManualScanStatus.INTERRUPTED)) {
            session.message?.takeIf { it.isNotBlank() }?.let { android.util.Log.w("BistScanActivity", "Manual scan state: $it") }
        }''',
    'BIST safe hero copy')

# Opportunity primary UI: same state source, but no raw session/backend text on terminal failures.
replace_once(
    'ui/OpportunityActivity.kt',
    '''                    ManualScanStatus.DATA_UNAVAILABLE -> {
                        opportunityUiDataState = OpportunityUiDataState.UNAVAILABLE
                        if (AppSession.lastOpportunities.isEmpty()) bindFiltered(emptyList(), "Veri yok / analiz bekleniyor • ${session.message.orEmpty()}")
                        else updateTopMetrics(AppSession.lastOpportunities)
                    }
                    ManualScanStatus.ERROR, ManualScanStatus.INTERRUPTED -> {
                        opportunityUiDataState = OpportunityUiDataState.ERROR
                        if (AppSession.lastOpportunities.isEmpty()) bindFiltered(emptyList(), "Analiz edilemedi • ${session.message.orEmpty()}")
                        else updateTopMetrics(AppSession.lastOpportunities)
                    }''',
    '''                    ManualScanStatus.DATA_UNAVAILABLE -> {
                        opportunityUiDataState = OpportunityUiDataState.UNAVAILABLE
                        session.message?.takeIf { it.isNotBlank() }?.let { android.util.Log.w("OpportunityActivity", "Manual scan unavailable: $it") }
                        if (AppSession.lastOpportunities.isEmpty()) bindFiltered(emptyList(), "Veri yok / analiz bekleniyor • Güncel piyasa verisi doğrulanamadı.")
                        else updateTopMetrics(AppSession.lastOpportunities)
                    }
                    ManualScanStatus.ERROR, ManualScanStatus.INTERRUPTED -> {
                        opportunityUiDataState = OpportunityUiDataState.ERROR
                        session.message?.takeIf { it.isNotBlank() }?.let { android.util.Log.w("OpportunityActivity", "Manual scan error: $it") }
                        if (AppSession.lastOpportunities.isEmpty()) bindFiltered(emptyList(), "Analiz tamamlanamadı • Bağlantı ve veri sağlayıcı durumunu kontrol edin.")
                        else updateTopMetrics(AppSession.lastOpportunities)
                    }''',
    'Opportunity safe terminal copy')

# Favorite cards: an old opportunity cannot look like a current LONG/SHORT signal when fresh quote/state is absent.
replace_once(
    'ui/FavoriteAdapter.kt',
    '''        val currentChange = stockChange(stock)
        val sourceLabel = stock?.let { sourceState(it) } ?: "Veri yenilenemedi"

        holder.details.text = buildString {''',
    '''        val currentChange = stockChange(stock)
        val sourceLabel = stock?.let { sourceState(it) } ?: "Veri yenilenemedi"
        val verifiedOpportunity = opportunity?.takeIf {
            currentPrice != null &&
                stock?.isRealtime == true &&
                it.isRealtime &&
                it.dataMode == tr.borsatakip.v5.model.DataMode.REALTIME &&
                it.decisionState == tr.borsatakip.v5.model.DecisionState.VERIFIED_OPPORTUNITY &&
                it.signalValidity == tr.borsatakip.v5.model.SignalValidity.VALID
        }

        holder.details.text = buildString {''',
    'Favorite verified signal gate')
replace_once(
    'ui/FavoriteAdapter.kt',
    '''            opportunity?.let {
                append("\\nV5 ").append(it.finalSignalScore).append("/100")
                append(" • Risk ").append(it.riskScore).append("/100")
                val interval = it.analysisTimeframeMinutes.takeIf { value -> ScanTimeframe.isSupportedStored(value) }
                    ?: fav.analysisIntervalMinutes
                interval?.let { value -> append(" • Analiz ").append(ScanTimeframe.displayLabel(value)) }
                append(" • Karar: ").append(it.direction)
            }''',
    '''            verifiedOpportunity?.let {
                append("\\nV5 ").append(it.finalSignalScore).append("/100")
                append(" • Risk ").append(it.riskScore).append("/100")
                val interval = it.analysisTimeframeMinutes.takeIf { value -> ScanTimeframe.isSupportedStored(value) }
                    ?: fav.analysisIntervalMinutes
                interval?.let { value -> append(" • Analiz ").append(ScanTimeframe.displayLabel(value)) }
                append(" • Karar: ").append(it.direction)
            }
            if (opportunity != null && verifiedOpportunity == null) {
                append("\\nSinyal doğrulanmadı • AL/SAT gösterilmiyor")
            }''',
    'Favorite verified signal rendering')

# Technical analysis: technical inspection may remain available, but LONG/SHORT publication is fail-closed.
replace_once(
    'ui/TechnicalAnalysisActivity.kt',
    '''        sig.text = "${x.direction} • Nihai Sinyal ${x.finalSignalScore}/100"
        score.text = "Teknik ${x.score} • Risk ${x.riskScore} • Veri Güveni ${x.dataConfidenceScore} • ${x.technicalLabel}"''',
    '''        val verifiedSignal = x.dataMode == tr.borsatakip.v5.model.DataMode.REALTIME &&
            x.isRealtime &&
            x.decisionState == tr.borsatakip.v5.model.DecisionState.VERIFIED_OPPORTUNITY &&
            x.signalValidity == tr.borsatakip.v5.model.SignalValidity.VALID
        sig.text = when {
            verifiedSignal -> "${x.direction} • Nihai Sinyal ${x.finalSignalScore}/100"
            x.signalValidity == tr.borsatakip.v5.model.SignalValidity.INSUFFICIENT ||
                x.decisionState == tr.borsatakip.v5.model.DecisionState.INSUFFICIENT_DATA -> "YETERSİZ VERİ • AL/SAT YOK"
            else -> "TEKNİK İZLEME • AL/SAT YOK"
        }
        score.text = "Teknik ${x.score} • Risk ${x.riskScore} • Veri Güveni ${x.dataConfidenceScore} • ${x.technicalLabel}"''',
    'Technical verified signal gate')
replace_once(
    'ui/TechnicalAnalysisActivity.kt',
    '''            } catch (t: Throwable) {
                status.text = "Çoklu zaman dilimi analizi alınamadı: ${t.message ?: "veri sağlayıcı hatası"}"''',
    '''            } catch (t: Throwable) {
                android.util.Log.w("TechnicalAnalysisActivity", "Multi-timeframe analysis failed", t)
                status.text = "Çoklu zaman dilimi analizi alınamadı. Veri sağlayıcı durumunu kontrol edin."''',
    'Technical raw exception copy')

# Stock detail: zero/negative is not a valid equity price; raw chart exception stays in log only.
replace_once(
    'ui/StockDetailActivity.kt',
    '''                chartStatus.text = "${timeframe.label}: gerçek OHLCV verisi alınamadı • ${error.message ?: error.javaClass.simpleName}"
                trendInfoPanel.text = "Trend analizi için yeterli veri yok."''',
    '''                android.util.Log.w("StockDetailActivity", "Chart load failed for ${timeframe.label}", error)
                chartStatus.text = "${timeframe.label}: gerçek OHLCV verisi alınamadı. Veri sağlayıcı durumunu kontrol edin."
                trendInfoPanel.text = "Trend analizi için yeterli veri yok."''',
    'Stock detail raw chart error')
replace_once(
    'ui/StockDetailActivity.kt',
    '    private fun money(v:Double)=if(v.isFinite())"%.2f TL".format(v) else "Veri yok"',
    '    private fun money(v:Double)=if(v.isFinite() && v > 0.0)"%.2f TL".format(v) else "Veri yok"',
    'Stock detail zero price gate')

# Notifications: network exception details are technical; primary UI stays actionable and concise.
replace_once(
    'ui/NotificationsActivity.kt',
    '''                .onFailure { e ->
                    status.text = "TradingView webhook sinyalleri alınamadı"
                    list.text = e.message ?: "Bilinmeyen bağlantı hatası"
                }''',
    '''                .onFailure { e ->
                    android.util.Log.w("NotificationsActivity", "TradingView signal load failed", e)
                    status.text = "TradingView webhook sinyalleri alınamadı"
                    list.text = "Bağlantı veya veri servisi şu anda kullanılamıyor. Daha sonra tekrar deneyin."
                }''',
    'Notifications raw exception copy')

# VIOP detail: preserve error object in logs, not in primary status text.
replace_once(
    'ui/ViopDetailActivity.kt',
    '''                setStatusMessage(
                    if (hasVerified) "Grafik yenilenemedi • doğrulanmış mevcut quote korunuyor."
                    else "VİOP quote/history doğrulanamadı • ${error.message ?: "veri alınamadı"}",
                    if (hasVerified) R.color.yellow else R.color.red
                )''',
    '''                android.util.Log.w("ViopDetailActivity", "VIOP quote/history validation failed", error)
                setStatusMessage(
                    if (hasVerified) "Grafik yenilenemedi • doğrulanmış mevcut quote korunuyor."
                    else "VİOP quote/history doğrulanamadı. Bağlantı ve veri sağlayıcı durumunu kontrol edin.",
                    if (hasVerified) R.color.yellow else R.color.red
                )''',
    'VIOP detail quote raw exception')
replace_once(
    'ui/ViopDetailActivity.kt',
    '''            } catch (error: Exception) {
                setStatusMessage("${spec.displayLabel} grafiği alınamadı • ${error.message ?: "veri yok"}", R.color.red)
            }''',
    '''            } catch (error: Exception) {
                android.util.Log.w("ViopDetailActivity", "VIOP chart load failed for ${spec.displayLabel}", error)
                setStatusMessage("${spec.displayLabel} grafiği alınamadı. Veri sağlayıcı durumunu kontrol edin.", R.color.red)
            }''',
    'VIOP detail chart raw exception')

print('UIUX_STATE_FAILCLOSED_APPLIED')
