package tr.borsatakip.v5.data

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExternalMarketQuoteLog(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("external_market_quote_log_v611", Context.MODE_PRIVATE)

    fun append(
        symbol: String,
        provider: String,
        stage: String,
        state: String,
        durationMs: Long? = null,
        errorCode: String? = null,
        detail: String? = null
    ) {
        val now = System.currentTimeMillis()
        val safeDetail = sanitize(detail.orEmpty())
        val line = buildString {
            append(now).append('|')
            append(symbol.trim().uppercase()).append('|')
            append(provider).append('|')
            append(stage).append('|')
            append(state)
            durationMs?.let { append("|durationMs=").append(it) }
            errorCode?.takeIf { it.isNotBlank() }?.let { append("|errorCode=").append(it) }
            if (safeDetail.isNotBlank()) append("|detail=").append(safeDetail.take(240))
        }
        Log.i(TAG, line)
        val lines = prefs.getString(KEY_LINES, "").orEmpty().lineSequence().filter { it.isNotBlank() }.toMutableList()
        lines += line
        prefs.edit().putString(KEY_LINES, lines.takeLast(MAX_LINES).joinToString("\n")).apply()
    }

    fun recent(limit: Int = 50): List<String> =
        prefs.getString(KEY_LINES, "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList().takeLast(limit.coerceIn(1, MAX_LINES))

    fun formatAttemptTime(epochMs: Long): String = if (epochMs > 0L) {
        SimpleDateFormat("HH:mm:ss", Locale("tr", "TR")).format(Date(epochMs))
    } else "—"

    private fun sanitize(raw: String): String = raw
        .replace(Regex("btk_[A-Za-z0-9_-]{8,}"), "[REDACTED]")
        .replace(Regex("(?i)authorization\\s*[:=]\\s*[^\\s]+"), "Authorization=[REDACTED]")
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()

    companion object {
        private const val TAG = "EXTERNAL_MARKET"
        private const val KEY_LINES = "lines"
        private const val MAX_LINES = 120
    }
}
