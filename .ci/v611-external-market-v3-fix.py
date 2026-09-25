from pathlib import Path
import sys

android = Path(sys.argv[1]).resolve()
src = android / 'app/src/main/java/tr/borsatakip/v5'
testsrc = android / 'app/src/test/java/tr/borsatakip/v5'
res = android / 'app/src/main/res'


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 occurrence, got {count}')
    path.write_text(text.replace(old, new, 1))

# 1) Provider ticker allowlist: an ONS ALTIN screen may only accept the spot XAU/USD ticker.
policy = src / 'data/ExternalMarketQuotePolicy.kt'
old = '''    fun capabilityMarket(symbol: String): String? = when (symbol.trim().uppercase()) {
        "USDTRY" -> "FX"
        "XAUUSD" -> "COMMODITY"
        else -> null
    }
'''
new = old + '''
    fun filterProviderTickers(symbol: String, providerTickers: List<String>): List<String> {
        val allowed = when (symbol.trim().uppercase()) {
            "USDTRY" -> setOf("TRY=X")
            "XAUUSD" -> setOf("XAUUSD=X")
            else -> emptySet()
        }
        return providerTickers
            .map { it.trim().uppercase() }
            .filter { it in allowed }
            .distinct()
    }
'''
replace_once(policy, old, new, 'ExternalMarketQuotePolicy ticker allowlist')

# 2) Enforce allowlist centrally and throttle repeated force-refresh taps.
service = src / 'data/ExternalMarketQuoteService.kt'
replace_once(
    service,
    '        val symbol = symbolLabel.trim().uppercase()\n        val configFingerprint = listOf(',
    '        val symbol = symbolLabel.trim().uppercase()\n        val safeProviderTickers = ExternalMarketQuotePolicy.filterProviderTickers(symbol, providerTickers)\n        val configFingerprint = listOf(',
    'ExternalMarketQuoteService safe ticker normalization'
)
replace_once(
    service,
    '        val key = "$symbol|${providerTickers.joinToString(",") { it.trim() }}|$configFingerprint"',
    '        val key = "$symbol|${safeProviderTickers.joinToString(",")}|$configFingerprint"',
    'ExternalMarketQuoteService memo key'
)
replace_once(
    service,
    '''            if (!forceRefresh) {
                memo[key]?.takeIf { insideNow - it.elapsedAt <= MEMO_TTL_MS }?.let {
                    return@withLock it.result.copy(fromMemoryMemo = true)
                }
            }
            val result = loadFresh(symbol, providerTickers, forceRefresh)
''',
    '''            if (!forceRefresh) {
                memo[key]?.takeIf { insideNow - it.elapsedAt <= MEMO_TTL_MS }?.let {
                    return@withLock it.result.copy(fromMemoryMemo = true)
                }
            } else {
                val lastForced = lastForcedRefreshAt[key]
                if (lastForced != null && insideNow - lastForced < FORCE_REFRESH_MIN_INTERVAL_MS) {
                    memo[key]?.let {
                        log.append(symbol, "external_market", "REFRESH", "THROTTLED", errorCode = "FORCE_REFRESH_THROTTLED")
                        return@withLock it.result.copy(fromMemoryMemo = true)
                    }
                }
                lastForcedRefreshAt[key] = insideNow
            }
            val result = loadFresh(symbol, safeProviderTickers, forceRefresh)
''',
    'ExternalMarketQuoteService force-refresh throttle'
)
replace_once(
    service,
    '''        private const val MEMO_TTL_MS = 15_000L
        private const val CAPABILITY_MEMO_TTL_MS = 15_000L
        private val memo = ConcurrentHashMap<String, Memo>()
''',
    '''        private const val MEMO_TTL_MS = 15_000L
        private const val CAPABILITY_MEMO_TTL_MS = 15_000L
        private const val FORCE_REFRESH_MIN_INTERVAL_MS = 2_000L
        private val memo = ConcurrentHashMap<String, Memo>()
        private val lastForcedRefreshAt = ConcurrentHashMap<String, Long>()
''',
    'ExternalMarketQuoteService throttle constants'
)

# 3) UI callers: never substitute COMEX futures GC=F for ONS ALTIN/XAUUSD.
for rel in ['ui/MainActivity.kt', 'ui/MarketInstrumentDetailActivity.kt', 'ui/ViopActivity.kt']:
    p = src / rel
    text = p.read_text()
    count = text.count('listOf("XAUUSD=X", "GC=F")')
    if count != 1:
        raise SystemExit(f'{rel}: expected one XAUUSD+GC=F call, got {count}')
    p.write_text(text.replace('listOf("XAUUSD=X", "GC=F")', 'listOf("XAUUSD=X")', 1))

# 4) Provider-settings button must open the actual Data Connection settings screen.
detail = src / 'ui/MarketInstrumentDetailActivity.kt'
replace_once(
    detail,
    '        findViewById<Button>(R.id.marketDetailSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }',
    '''        findViewById<Button>(R.id.marketDetailSettings).setOnClickListener {
            startActivity(
                Intent(this, SettingsDetailActivity::class.java)
                    .putExtra(SettingsDetailActivity.EXTRA_MODE, SettingsDetailActivity.MODE_DATA_CONNECTION)
            )
        }''',
    'MarketInstrumentDetail direct provider settings'
)
layout = res / 'layout/activity_market_instrument_detail.xml'
replace_once(
    layout,
    'android:text="VERİ SAĞLAYICIYI AYARLA"',
    'android:text="VERİ SAĞLAYICILARINI AYARLA"',
    'MarketInstrumentDetail provider settings label'
)

# 5) Regression tests for asset identity.
test = testsrc / 'data/ExternalMarketQuotePolicyTest.kt'
insert = '''
    @Test fun providerTickerAllowlistNeverSubstitutesGoldFutureForSpotOunce() {
        assertEquals(listOf("XAUUSD=X"), ExternalMarketQuotePolicy.filterProviderTickers("XAUUSD", listOf("XAUUSD=X", "GC=F")))
        assertEquals(emptyList<String>(), ExternalMarketQuotePolicy.filterProviderTickers("XAUUSD", listOf("GC=F")))
        assertEquals(listOf("TRY=X"), ExternalMarketQuotePolicy.filterProviderTickers("USDTRY", listOf("TRY=X", "USDTRY=X")))
    }
'''
text = test.read_text()
if 'providerTickerAllowlistNeverSubstitutesGoldFutureForSpotOunce' in text:
    raise SystemExit('ExternalMarketQuotePolicyTest: V3 test already present')
pos = text.rfind('\n}')
if pos < 0:
    raise SystemExit('ExternalMarketQuotePolicyTest: closing brace not found')
test.write_text(text[:pos] + insert + text[pos:])

# Final fail-closed guards.
for rel in ['ui/MainActivity.kt', 'ui/MarketInstrumentDetailActivity.kt', 'ui/ViopActivity.kt']:
    if 'GC=F' in (src / rel).read_text():
        raise SystemExit(f'{rel}: GC=F remains in ONS flow')
if 'FORCE_REFRESH_MIN_INTERVAL_MS = 2_000L' not in service.read_text():
    raise SystemExit('ExternalMarketQuoteService: throttle marker missing')
if 'filterProviderTickers(symbol, providerTickers)' not in service.read_text():
    raise SystemExit('ExternalMarketQuoteService: ticker allowlist marker missing')
if 'VERİ SAĞLAYICILARINI AYARLA' not in layout.read_text():
    raise SystemExit('MarketInstrumentDetail: plural provider settings label missing')

print('EXTERNAL_MARKET_V3_FIX_APPLIED')
