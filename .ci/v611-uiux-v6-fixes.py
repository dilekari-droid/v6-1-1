from pathlib import Path
import subprocess, sys

android = Path(sys.argv[1]).resolve()
repo = android.parent
root = android / 'app/src/main/java/tr/borsatakip/v5'


def replace_once(path: Path, before: str, after: str, label: str):
    text = path.read_text()
    count = text.count(before)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 occurrence, got {count}')
    path.write_text(text.replace(before, after, 1))

models = subprocess.check_output([
    'git', '-C', str(repo), 'show',
    'HEAD:android/app/src/main/java/tr/borsatakip/v5/research/ResearchModels.kt'
], text=True)
(root / 'research/ResearchModels.kt').write_text(models)

(root / 'data/SessionRefreshGate.kt').write_text('''package tr.borsatakip.v5.data\n\nimport kotlinx.coroutines.sync.Mutex\nimport kotlinx.coroutines.sync.withLock\n\n/** Process-wide refresh single-flight primitive. */\nclass SessionRefreshGate {\n    private val mutex = Mutex()\n\n    suspend fun run(\n        isSatisfied: () -> Boolean,\n        refresh: suspend () -> Boolean\n    ): Boolean = mutex.withLock {\n        if (isSatisfied()) true else refresh()\n    }\n}\n''')

settings = root / 'data/SettingsStore.kt'
lines = settings.read_text().splitlines()
starts = [i for i, line in enumerate(lines) if line.strip() == 'companion object {']
if len(starts) != 2:
    raise SystemExit(f'SettingsStore expected 2 companion objects, got {len(starts)}')

def block_end(start):
    depth = 0
    for i in range(start, len(lines)):
        depth += lines[i].count('{') - lines[i].count('}')
        if depth == 0:
            return i
    raise SystemExit('SettingsStore companion object is not closed')

first_start, second_start = starts
first_end = block_end(first_start)
second_end = block_end(second_start)
second_body = lines[second_start + 1:second_end]
lines = lines[:second_start] + lines[second_end + 1:]
lines = lines[:first_end] + second_body + lines[first_end:]
settings.write_text('\n'.join(lines) + '\n')

old_scope = 'stock?.candles?.takeIf { it.size >= 2 }?.get(it.lastIndex - 1)?.close'
new_scope = 'stock?.candles?.takeIf { it.size >= 2 }?.let { it[it.lastIndex - 1].close }'
for rel in ('ui/BistScanActivity.kt', 'ui/OpportunityActivity.kt', 'ui/ViopActivity.kt'):
    replace_once(root / rel, old_scope, new_scope, f'{rel} scope')

def replace_https(rel: str, signature: str):
    path = root / rel
    src = path.read_text().splitlines()
    starts = [i for i, line in enumerate(src) if line.strip() == signature]
    if len(starts) != 1:
        raise SystemExit(f'{rel}: validator signature count={len(starts)}')
    start = starts[0]
    ends = [i for i in range(start, min(len(src), start + 30)) if src[i].strip() == '}.getOrDefault(false)']
    if len(ends) != 1:
        raise SystemExit(f'{rel}: validator end not found')
    end = ends[0]
    name = 'validHttps' if 'validHttps' in signature else 'isValidHttps'
    replacement = [
        f'        fun {name}(raw: String): Boolean = runCatching {{',
        '            val uri = URI(raw.trim())',
        '            val host = uri.host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]").orEmpty()',
        '            uri.scheme.equals("https", ignoreCase = true) &&',
        '                host.isNotBlank() &&',
        '                uri.userInfo == null &&',
        '                uri.fragment == null &&',
        '                uri.rawQuery == null &&',
        '                host != "localhost" && !host.endsWith(".localhost") &&',
        '                host !in setOf("127.0.0.1", "0.0.0.0", "::1", "10.0.2.2")',
        '        }.getOrDefault(false)',
    ]
    path.write_text('\n'.join(src[:start] + replacement + src[end + 1:]) + '\n')

replace_https('data/BackendPreflightClient.kt', 'fun validHttps(raw: String): Boolean = runCatching {')
replace_https('data/ProviderReadinessService.kt', 'fun isValidHttps(raw: String): Boolean = runCatching {')

test = android / 'app/src/test/java/tr/borsatakip/v5/data/BackendUrlPolicyTest.kt'
test.parent.mkdir(parents=True, exist_ok=True)
test.write_text('''package tr.borsatakip.v5.data\n\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass BackendUrlPolicyTest {\n    @Test fun acceptsCleanPublicHttpsOrigin() {\n        assertTrue(BackendPreflightClient.validHttps("https://backend.example.com"))\n        assertTrue(ProviderReadinessService.isValidHttps("https://backend.example.com"))\n    }\n\n    @Test fun rejectsUnsafeOrAmbiguousOrigins() {\n        listOf(\n            "http://backend.example.com",\n            "https://localhost",\n            "https://127.0.0.1",\n            "https://[::1]",\n            "https://10.0.2.2",\n            "https://user:pass@backend.example.com",\n            "https://backend.example.com?target=other",\n            "https://backend.example.com#fragment"\n        ).forEach {\n            assertFalse(it, BackendPreflightClient.validHttps(it))\n            assertFalse(it, ProviderReadinessService.isValidHttps(it))\n        }\n    }\n}\n''')

xml = android / 'app/src/main/res/layout/activity_viop.xml'
text = xml.read_text()
for before, after in {
    'android:text="VİOP LONG\\n0"': 'android:text="VİOP LONG\\n—"',
    'android:text="VİOP SHORT\\n0"': 'android:text="VİOP SHORT\\n—"',
    'android:text="İZLE\\n0"': 'android:text="İZLE\\n—"',
    'android:text="TARANAN\\n0"': 'android:text="TARANAN\\n—"',
}.items():
    if text.count(before) != 1:
        raise SystemExit(f'activity_viop.xml expected once: {before!r}, got {text.count(before)}')
    text = text.replace(before, after)
xml.write_text(text)

viop = root / 'ui/ViopActivity.kt'
replace_once(
    viop,
    '        setContentView(R.layout.activity_viop)\n        setupBottomNav()',
    '        setContentView(R.layout.activity_viop)\n        showUnavailableSummary("Veri bekleniyor")\n        setupBottomNav()',
    'ViopActivity initial state')

stocks = root / 'ui/StocksActivity.kt'
replace_once(stocks, '    private var filter: UiFilter = UiFilter.ALL\n',
             '    private var filter: UiFilter = UiFilter.ALL\n    private var marketSummaryReady: Boolean = false\n', 'Stocks field')
replace_once(stocks,
'''        bindSearch()\n        bindFilters()\n        renderIndexCards(AppSession.lastOpportunities)\n\n        lifecycleScope.launch {\n            repo.migrateLegacyIfNeeded()\n            bindList()\n            refreshIndexCards()\n        }''',
'''        bindSearch()\n        bindFilters()\n        showIndexCardsUnavailable()\n\n        lifecycleScope.launch {\n            repo.migrateLegacyIfNeeded()\n            refreshIndexCards()\n        }''', 'Stocks onCreate')
replace_once(stocks, '        val source = AppSession.lastOpportunities\n',
             '        val source = if (marketSummaryReady) AppSession.lastOpportunities else emptyList()\n', 'Stocks source gate')
replace_once(stocks,
'        status.text = when {\n            source.isEmpty() -> "Veri yok • Gerçek BIST taraması çalıştırıldığında sonuçlar burada gösterilir. • $providerText"',
'        status.text = when {\n            !marketSummaryReady -> "Veri doğrulanamadı • Güncel BIST endeks/veri kaynağı hazır değil. Fiyat, grafik ve LONG/SHORT sonucu gösterilmiyor."\n            source.isEmpty() -> "Veri yok • Gerçek BIST taraması çalıştırıldığında sonuçlar burada gösterilir. • $providerText"', 'Stocks status')
replace_once(stocks,
'''        renderIndexCards(source)\n    }\n\n    private suspend fun refreshIndexCards() {\n        val stocks = withContext(Dispatchers.IO) {\n            BistIndexDataService(this@StocksActivity).loadMany(listOf("XU100", "XU030", "XUTUM"))\n        }\n        stocks["XU100"]?.let { bindIndexStock(it, R.id.index100Value, R.id.index100Change, R.id.index100Chart) }\n        stocks["XU030"]?.let { bindIndexStock(it, R.id.index30Value, R.id.index30Change, R.id.index30Chart) }\n        stocks["XUTUM"]?.let { bindIndexStock(it, R.id.indexAllValue, R.id.indexAllChange, R.id.indexAllChart) }\n        if (::repo.isInitialized && ::list.isInitialized) bindList()\n    }''',
'''    }\n\n    private suspend fun refreshIndexCards() {\n        val stocks = withContext(Dispatchers.IO) {\n            BistIndexDataService(this@StocksActivity).loadMany(listOf("XU100", "XU030", "XUTUM"))\n        }\n        marketSummaryReady = listOf("XU100", "XU030", "XUTUM").all { symbol ->\n            val stock = stocks[symbol] ?: return@all false\n            val price = stock.quotePrice ?: stock.candles.lastOrNull()?.close\n            price != null && price.isFinite() && price > 0.0\n        }\n        showIndexCardsUnavailable()\n        if (marketSummaryReady) {\n            bindIndexStock(stocks.getValue("XU100"), R.id.index100Value, R.id.index100Change, R.id.index100Chart)\n            bindIndexStock(stocks.getValue("XU030"), R.id.index30Value, R.id.index30Change, R.id.index30Chart)\n            bindIndexStock(stocks.getValue("XUTUM"), R.id.indexAllValue, R.id.indexAllChange, R.id.indexAllChart)\n        }\n        if (::repo.isInitialized && ::list.isInitialized) bindList()\n    }''', 'Stocks refresh')
helper='''    private fun showIndexCardsUnavailable() {\n        listOf(\n            Triple(R.id.index100Value, R.id.index100Change, R.id.index100Chart),\n            Triple(R.id.index30Value, R.id.index30Change, R.id.index30Chart),\n            Triple(R.id.indexAllValue, R.id.indexAllChange, R.id.indexAllChart)\n        ).forEach { (valueId, changeId, chartId) ->\n            findViewById<TextView>(valueId).text = "Veri yok"\n            findViewById<TextView>(changeId).apply { text = "—"; setTextColor(getColor(R.color.text_secondary)) }\n            findViewById<StockSparklineView>(chartId).setCandles(emptyList(), null)\n        }\n    }\n\n'''
replace_once(stocks, '    private fun renderIndexCards(items: List<Opportunity>) {', helper + '    private fun renderIndexCards(items: List<Opportunity>) {', 'Stocks helper')
replace_once(stocks,
'''            lifecycleScope.launch {\n                bindList()\n                refreshIndexCards()\n            }''',
'            lifecycleScope.launch { refreshIndexCards() }', 'Stocks onResume')

adapter = root / 'ui/StocksCompactAdapter.kt'
replace_once(adapter,
'''        holder.direction.text = when (effectiveDirection) {\n            OpportunityDirectionalFilterPolicy.Direction.LONG -> if (verified) "LONG" else "LONG EĞİLİMİ"\n            OpportunityDirectionalFilterPolicy.Direction.SHORT -> if (verified) "SHORT" else "SHORT EĞİLİMİ"\n            OpportunityDirectionalFilterPolicy.Direction.NEUTRAL -> "NÖTR"\n        }''',
'''        val directionScore = OpportunityDirectionalFilterPolicy.scoreFor(item, effectiveDirection).coerceIn(0, 100)\n        holder.direction.text = when (effectiveDirection) {\n            OpportunityDirectionalFilterPolicy.Direction.LONG -> "${if (verified) "LONG" else "LONG EĞİLİMİ"} • Güç $directionScore"\n            OpportunityDirectionalFilterPolicy.Direction.SHORT -> "${if (verified) "SHORT" else "SHORT EĞİLİMİ"} • Güç $directionScore"\n            OpportunityDirectionalFilterPolicy.Direction.NEUTRAL -> "NÖTR"\n        }''', 'Stocks strength')

replace_once(viop,
    '        status.text = "Provider doğrulanıyor: HTTPS → Health → Authentication → VİOP Contracts → Quote → History"',
    '        status.text = "VİOP veri kaynağı doğrulanıyor…"', 'VIOP validating copy')
replace_once(viop,
'''                showUnavailableSummary("VİOP provider doğrulanamadı")\n                status.text = "VİOP provider doğrulanamadı • ${r.failureCode} • ${r.message}"''',
'''                showUnavailableSummary("VİOP provider doğrulanamadı")\n                android.util.Log.w("ViopActivity", "Provider validation failed: ${r.failureCode} • ${r.message}")\n                status.text = "VERİ DOĞRULANAMADI\\nPiyasa veri kaynağından güncel VİOP verisi alınamıyor.\\nSon doğrulama: ${timeOf(System.currentTimeMillis())}"''', 'VIOP friendly error')

text = xml.read_text()
start = text.index('        <!-- Provider state is a separate full-width row. -->')
end = text.index('        <!-- Mode cards: production publication and underlying bias are explicitly separated. -->')
provider = '''        <!-- Provider state: text is never squeezed by the validation action on narrow screens. -->\n        <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:background="@drawable/bg_card_blue" android:orientation="vertical" android:padding="10dp">\n            <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:gravity="center_vertical" android:orientation="horizontal">\n                <TextView android:id="@+id/providerDot" android:layout_width="24dp" android:layout_height="match_parent" android:gravity="center" android:text="●" android:textColor="@color/text_secondary" android:textSize="15sp" />\n                <LinearLayout android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:orientation="vertical" android:paddingStart="6dp">\n                    <TextView android:id="@+id/providerTitle" android:layout_width="match_parent" android:layout_height="wrap_content" android:maxLines="2" android:text="VİOP VERİ SERVİSİ KONTROL EDİLİYOR" android:textColor="@color/text_primary" android:textSize="12sp" android:textStyle="bold" />\n                    <TextView android:id="@+id/providerStatus" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="2dp" android:maxLines="4" android:text="Veri kaynağı kontrol ediliyor" android:textColor="@color/text_secondary" android:textSize="9sp" />\n                </LinearLayout>\n            </LinearLayout>\n            <Button android:id="@+id/refresh" style="@style/ScanFilterButton" android:layout_width="match_parent" android:layout_height="44dp" android:layout_marginTop="6dp" android:minHeight="44dp" android:text="Bağlantıyı Doğrula" android:textAllCaps="false" android:textSize="10sp" />\n        </LinearLayout>\n\n'''
text = text[:start] + provider + text[end:]

pos = text.index('android:id="@+id/filterAll"')
start = text.rfind('        <HorizontalScrollView', 0, pos)
end = text.index('        </HorizontalScrollView>', pos) + len('        </HorizontalScrollView>')
filters = '''        <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="6dp" android:orientation="vertical">\n            <LinearLayout android:layout_width="match_parent" android:layout_height="48dp" android:orientation="horizontal">\n                <Button android:id="@+id/filterAll" style="@style/ScanFilterButton" android:layout_width="0dp" android:layout_height="48dp" android:layout_weight="1" android:minHeight="48dp" android:text="TÜMÜ" android:textSize="9sp" />\n                <Button android:id="@+id/filterLong" style="@style/ScanFilterButton" android:layout_width="0dp" android:layout_height="48dp" android:layout_weight="1" android:layout_marginStart="4dp" android:minHeight="48dp" android:text="LONG" android:textSize="9sp" />\n                <Button android:id="@+id/filterShort" style="@style/ScanFilterButton" android:layout_width="0dp" android:layout_height="48dp" android:layout_weight="1" android:layout_marginStart="4dp" android:minHeight="48dp" android:text="SHORT" android:textSize="9sp" />\n                <Button android:id="@+id/filterWatch" style="@style/ScanFilterButton" android:layout_width="0dp" android:layout_height="48dp" android:layout_weight="1" android:layout_marginStart="4dp" android:minHeight="48dp" android:text="İZLE" android:textSize="9sp" />\n            </LinearLayout>\n            <Button android:id="@+id/detailedFilter" style="@style/ScanFilterButton" android:layout_width="match_parent" android:layout_height="44dp" android:layout_marginTop="4dp" android:minHeight="44dp" android:text="Detaylı Filtre" android:textAllCaps="false" android:textSize="9sp" />\n        </LinearLayout>'''
text = text[:start] + filters + text[end:]
xml.write_text(text)
