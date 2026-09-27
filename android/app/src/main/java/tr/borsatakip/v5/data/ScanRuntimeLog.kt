package tr.borsatakip.v5.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent bounded audit log for real foreground/background scan lifecycle events. */
object ScanRuntimeLog {
    const val FILE_NAME = "scan_runtime_events.log"
    private const val TAG = "SCAN_RUNTIME"
    private const val MAX_BYTES = 512_000L
    private const val KEEP_LINES = 1_500
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun event(context: Context, event: String, detail: String = "") {
        val cleanEvent = event.trim().replace(Regex("[^A-Z0-9_]+"), "_")
        val cleanDetail = detail.replace('\n', ' ').trim()
        val line = buildString {
            append(formatter.format(Date()))
            append(' ')
            append(cleanEvent)
            if (cleanDetail.isNotBlank()) { append(' '); append(cleanDetail) }
        }
        Log.i(TAG, line)
        runCatching {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            if (file.isFile && file.length() > MAX_BYTES) {
                val tail = file.readLines().takeLast(KEEP_LINES)
                file.writeText(tail.joinToString("\n", postfix = if (tail.isEmpty()) "" else "\n"), Charsets.UTF_8)
            }
            file.appendText(line + "\n", Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "scan log write failed: ${it.message}") }
    }

    @Synchronized
    fun tail(context: Context, maxLines: Int = 200): List<String> {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.isFile) return emptyList()
        return runCatching { file.readLines().takeLast(maxLines.coerceIn(1, KEEP_LINES)) }.getOrDefault(emptyList())
    }
}
