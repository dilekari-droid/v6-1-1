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

# BIST scan: user-visible state labels are localized; no raw internal ERROR/SCANNING tokens.
bist = root / 'ui/BistScanActivity.kt'
for before, after in {
    'ManualScanStatus.RUNNING -> "SCANNING"': 'ManualScanStatus.RUNNING -> "TARANIYOR"',
    'ManualScanStatus.PAUSED -> "PAUSED"': 'ManualScanStatus.PAUSED -> "DURAKLATILDI"',
    'ManualScanStatus.COMPLETED -> "COMPLETED"': 'ManualScanStatus.COMPLETED -> "TAMAMLANDI"',
    'ManualScanStatus.STOPPED -> "STOPPED"': 'ManualScanStatus.STOPPED -> "DURDURULDU"',
    'ManualScanStatus.ERROR -> "ERROR"': 'ManualScanStatus.ERROR -> "HATA"',
}.items():
    replace_once('ui/BistScanActivity.kt', before, after, 'BIST state label')

replace_once(
    'ui/BistScanActivity.kt',
    'ProviderFailureCode.EMPTY_DATA, ProviderFailureCode.BIST_HISTORY_ERROR, ProviderFailureCode.STALE_DATA -> "${timeframe.label} periyodu seçildi ancak gerçek OHLCV hazır değil. ${detail.orEmpty()}"',
    'ProviderFailureCode.EMPTY_DATA, ProviderFailureCode.BIST_HISTORY_ERROR, ProviderFailureCode.STALE_DATA -> "${timeframe.label} periyodu seçildi ancak gerçek OHLCV hazır değil. Ayrıntılar için veri sağlayıcı durumunu kontrol edin."',
    'BIST raw detail empty/stale')
replace_once(
    'ui/BistScanActivity.kt',
    'else -> "${timeframe.label} veri servisine ulaşılamadı. ${detail.orEmpty()}"',
    'else -> "${timeframe.label} veri servisine ulaşılamadı. Bağlantıyı yeniden doğrulayın."',
    'BIST raw detail generic')

# Settings: keep failureCode for technical detail screens only, not Toast copy.
replace_once(
    'ui/SettingsActivity.kt',
    'if (result.state == ProviderState.PROVIDER_READY || (result.state == ProviderState.PROVIDER_STALE_READY && result.failureCode == ProviderFailureCode.NONE)) "Provider bağlantısı doğrulandı: ${result.message}" else "Veri yenilenemedi: ${result.failureCode} • ${result.message}"',
    'if (result.state == ProviderState.PROVIDER_READY || (result.state == ProviderState.PROVIDER_STALE_READY && result.failureCode == ProviderFailureCode.NONE)) "Provider bağlantısı doğrulandı: ${result.message}" else "Veri yenilenemedi. Bağlantı ve veri sağlayıcı ayarlarını kontrol edin."',
    'Settings refresh toast')
replace_once(
    'ui/SettingsActivity.kt',
    'Toast.makeText(this@SettingsActivity, "$prefix: ${result.failureCode} • ${result.message}", Toast.LENGTH_LONG).show()',
    'Toast.makeText(this@SettingsActivity, "$prefix. Bağlantı ve veri sağlayıcı ayarlarını kontrol edin.", Toast.LENGTH_LONG).show()',
    'Settings validate toast')

# Signal history: internal enum names are not UI copy.
signal = root / 'ui/SignalHistoryActivity.kt'
for before, after in {
    'HistoryLoadState.NO_HISTORY -> "NO_HISTORY • Geçerli sinyal geçmişi yok.\\n$filterInfo"': 'HistoryLoadState.NO_HISTORY -> "Geçerli sinyal geçmişi yok.\\n$filterInfo"',
    'HistoryLoadState.PARTIAL_HISTORY -> "PARTIAL_HISTORY • Kısmi taramadan ${overview.totalRecords} geçerli sinyal kaydı mevcut.\\n$filterInfo"': 'HistoryLoadState.PARTIAL_HISTORY -> "Kısmi tarama geçmişi • ${overview.totalRecords} geçerli sinyal kaydı mevcut.\\n$filterInfo"',
    'HistoryLoadState.COMPLETE_HISTORY -> "COMPLETE_HISTORY • ${overview.totalRecords} geçerli sinyal kaydı mevcut.\\n$filterInfo"': 'HistoryLoadState.COMPLETE_HISTORY -> "Sinyal geçmişi • ${overview.totalRecords} geçerli sinyal kaydı mevcut.\\n$filterInfo"',
    'HistoryLoadState.HISTORY_LOAD_ERROR -> "HISTORY_LOAD_ERROR • Sinyal geçmişi okunamadı."': 'HistoryLoadState.HISTORY_LOAD_ERROR -> "Sinyal geçmişi okunamadı. Teknik ayrıntılar için Ayarlar bölümünü kontrol edin."',
}.items():
    replace_once('ui/SignalHistoryActivity.kt', before, after, 'Signal history user copy')

# VIOP: preserve technical failure code in logs/state, never in primary user-facing status copy.
replace_once(
    'ui/ViopActivity.kt',
    'status.text = "VİOP provider otomatik doğrulanıyor: HTTPS → Health → Authentication → Contracts → Quote → History"',
    'status.text = "VİOP veri kaynağı doğrulanıyor…"',
    'VIOP auto-validation copy')
replace_once(
    'ui/ViopActivity.kt',
    'else status.text = "${result.failureCode} • ${result.message}"',
    'else { android.util.Log.w("ViopActivity", "Provider auto validation failed: ${result.failureCode} • ${result.message}"); status.text = "VERİ DOĞRULANAMADI\\nGüncel VİOP verisi alınamıyor. Bağlantıyı yeniden doğrulayın." }',
    'VIOP auto-validation failure')
replace_once(
    'ui/ViopActivity.kt',
    'else -> "${s.failureCode} • ${s.message}"',
    'else -> "Veri doğrulanamadı • gerçek VİOP sinyali yayımlanmaz"',
    'VIOP provider card raw code')
replace_once(
    'ui/ViopActivity.kt',
    'else -> "VİOP veri servisi hazır değil • ${local.failureCode} • dayanak detayı açıldı."',
    'else -> "VİOP veri servisi hazır değil • dayanak detayı açıldı."',
    'VIOP underlying fallback raw code')

print('UIUX_COPY_FIXES_APPLIED')
