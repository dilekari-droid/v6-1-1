from pathlib import Path
import sys

android = Path(sys.argv[1]).resolve()
path = android / 'app/src/main/java/tr/borsatakip/v5/ui/FavoritesActivity.kt'
text = path.read_text()

old = '''            val integrity = RealTimeIntegrityPolicy.validate(stock)\n            val scored = OpportunityEngine.score(stock)\n            val opportunity = when {\n                scored == null -> fallbackOpportunity(stock)\n                integrity.accepted -> scored\n                else -> OpportunityPublicationPolicy.delayedObservation(scored, integrity.reason)\n            }\n            openOpportunity(opportunity, stock)'''
new = '''            val displayPrice = stock.quotePrice ?: stock.candles.lastOrNull()?.close\n            if (displayPrice == null || !displayPrice.isFinite() || displayPrice <= 0.0) {\n                Toast.makeText(this@FavoritesActivity, "$symbol için güncel fiyat verisi yok", Toast.LENGTH_LONG).show()\n                return@launch\n            }\n            val integrity = RealTimeIntegrityPolicy.validate(stock)\n            val scored = OpportunityEngine.score(stock)\n            if (scored == null) {\n                Toast.makeText(this@FavoritesActivity, "$symbol için teknik analiz üretmeye yeterli gerçek veri yok", Toast.LENGTH_LONG).show()\n                return@launch\n            }\n            val opportunity = if (integrity.accepted) scored else OpportunityPublicationPolicy.delayedObservation(scored, integrity.reason)\n            openOpportunity(opportunity, stock)'''
if text.count(old) != 1:
    raise SystemExit(f'Favorites openStock branch expected once, got {text.count(old)}')
text = text.replace(old, new, 1)

start_marker = '    private fun fallbackOpportunity(stock: Stock): Opportunity {'
end_marker = '    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()'
if text.count(start_marker) != 1 or text.count(end_marker) != 1:
    raise SystemExit('Favorites fallback block markers not unique')
start = text.index(start_marker)
end = text.index(end_marker)
text = text[:start] + text[end:]
path.write_text(text)
print('FAVORITES_FAILCLOSED_APPLIED')
