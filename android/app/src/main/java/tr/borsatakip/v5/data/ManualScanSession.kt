package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.scan.BistScanMode
import tr.borsatakip.v5.scan.ScanPhase
import tr.borsatakip.v5.scan.ScanState
import tr.borsatakip.v5.scan.ScanStatus
import java.util.UUID

/**
 * Kullanıcı tarafından başlatılan manuel BIST taramasının Activity'den bağımsız gerçek state'i.
 * Progress saklanmaz/uydurulmaz; her zaman scannedCount / totalCount üzerinden hesaplanır.
 */
enum class ManualScanStatus {
    IDLE,
    PREFLIGHT,
    RUNNING,
    PAUSED,
    FINALIZING,
    COMPLETED,
    PARTIAL,
    STOPPED,
    ERROR,
    DATA_UNAVAILABLE,
    INTERRUPTED
}

enum class ManualDataQuality { UNKNOWN, READY, PARTIAL, STALE, NO_DATA, ERROR }

data class ManualScanSession(
    val scanId: String = "",
    val status: ManualScanStatus = ManualScanStatus.IDLE,
    val phase: String = ScanPhase.IDLE.name,
    val scannedCount: Int = 0,
    val totalCount: Int = 0,
    val longCount: Int = 0,
    val shortCount: Int = 0,
    val watchCount: Int = 0,
    val currentSymbol: String? = null,
    val providerStatus: String = "DOĞRULANMADI",
    val dataQuality: ManualDataQuality = ManualDataQuality.UNKNOWN,
    val analysisTimeframeMinutes: Int = 5,
    val scanMode: String = BistScanMode.REALTIME_ONLY.name,
    val startedAt: Long = 0L,
    val lastUpdate: Long = 0L,
    val completedAt: Long? = null,
    val resultCount: Int = 0,
    val runStatus: String? = null,
    val message: String? = null
) {
    val progress: Int
        get() = if (totalCount <= 0) 0 else ((scannedCount.coerceIn(0, totalCount) * 100L) / totalCount).toInt().coerceIn(0, 100)

    val isActive: Boolean
        get() = status in setOf(ManualScanStatus.PREFLIGHT, ManualScanStatus.RUNNING, ManualScanStatus.PAUSED, ManualScanStatus.FINALIZING)
}

/** SharedPreferences yalnız küçük scan-session metadatası için kullanılır; sonuç listesi ayrı store'da kalır. */
class ManualScanSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("manual_scan_session_v1", Context.MODE_PRIVATE)

    fun load(): ManualScanSession {
        val scanId = prefs.getString("scanId", "").orEmpty()
        if (scanId.isBlank()) return ManualScanSession()
        return ManualScanSession(
            scanId = scanId,
            status = enumValueOrDefault(prefs.getString("status", null), ManualScanStatus.IDLE),
            phase = prefs.getString("phase", ScanPhase.IDLE.name).orEmpty(),
            scannedCount = prefs.getInt("scannedCount", 0).coerceAtLeast(0),
            totalCount = prefs.getInt("totalCount", 0).coerceAtLeast(0),
            longCount = prefs.getInt("longCount", 0).coerceAtLeast(0),
            shortCount = prefs.getInt("shortCount", 0).coerceAtLeast(0),
            watchCount = prefs.getInt("watchCount", 0).coerceAtLeast(0),
            currentSymbol = prefs.getString("currentSymbol", null)?.takeIf { it.isNotBlank() },
            providerStatus = prefs.getString("providerStatus", "DOĞRULANMADI").orEmpty(),
            dataQuality = enumValueOrDefault(prefs.getString("dataQuality", null), ManualDataQuality.UNKNOWN),
            analysisTimeframeMinutes = prefs.getInt("analysisTimeframeMinutes", 5),
            scanMode = prefs.getString("scanMode", BistScanMode.REALTIME_ONLY.name).orEmpty(),
            startedAt = prefs.getLong("startedAt", 0L),
            lastUpdate = prefs.getLong("lastUpdate", 0L),
            completedAt = prefs.getLong("completedAt", 0L).takeIf { it > 0L },
            resultCount = prefs.getInt("resultCount", 0).coerceAtLeast(0),
            runStatus = prefs.getString("runStatus", null)?.takeIf { it.isNotBlank() },
            message = prefs.getString("message", null)?.takeIf { it.isNotBlank() }
        )
    }

    fun save(session: ManualScanSession, synchronous: Boolean): Boolean {
        val editor = prefs.edit()
            .putString("scanId", session.scanId)
            .putString("status", session.status.name)
            .putString("phase", session.phase)
            .putInt("scannedCount", session.scannedCount)
            .putInt("totalCount", session.totalCount)
            .putInt("longCount", session.longCount)
            .putInt("shortCount", session.shortCount)
            .putInt("watchCount", session.watchCount)
            .putString("currentSymbol", session.currentSymbol.orEmpty())
            .putString("providerStatus", session.providerStatus)
            .putString("dataQuality", session.dataQuality.name)
            .putInt("analysisTimeframeMinutes", session.analysisTimeframeMinutes)
            .putString("scanMode", session.scanMode)
            .putLong("startedAt", session.startedAt)
            .putLong("lastUpdate", session.lastUpdate)
            .putLong("completedAt", session.completedAt ?: 0L)
            .putInt("resultCount", session.resultCount)
            .putString("runStatus", session.runStatus.orEmpty())
            .putString("message", session.message.orEmpty())
        return if (synchronous) editor.commit() else { editor.apply(); true }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, fallback: T): T =
        runCatching { enumValueOf<T>(raw.orEmpty()) }.getOrDefault(fallback)
}

object ManualScanRecoveryPolicy {
    fun recover(session: ManualScanSession, nowMs: Long = System.currentTimeMillis()): ManualScanSession {
        if (!session.isActive) return session
        return session.copy(
            status = ManualScanStatus.INTERRUPTED,
            phase = ScanPhase.ERROR.name,
            dataQuality = ManualDataQuality.ERROR,
            lastUpdate = nowMs,
            completedAt = nowMs,
            message = "Önceki tarama süreci uygulama/OS tarafından kesildi; sahte COMPLETED üretilmedi."
        )
    }
}

object ManualScanPersistencePolicy {
    const val MIN_PERSIST_INTERVAL_MS = 750L
    const val MIN_PROGRESS_DELTA = 10

    fun shouldPersist(
        force: Boolean,
        scanIdChanged: Boolean,
        nowMs: Long,
        lastPersistAtMs: Long,
        processed: Int,
        lastPersistedProcessed: Int
    ): Boolean = force || scanIdChanged ||
        nowMs - lastPersistAtMs >= MIN_PERSIST_INTERVAL_MS ||
        lastPersistedProcessed < 0 || processed - lastPersistedProcessed >= MIN_PROGRESS_DELTA
}

class ManualScanSessionRepository private constructor(context: Context) {
    private val store = ManualScanSessionStore(context)
    private val lock = Any()
    private val mutable = MutableStateFlow(ManualScanRecoveryPolicy.recover(store.load()))
    val state: StateFlow<ManualScanSession> = mutable.asStateFlow()
    private var lastPersistAt = 0L
    private var lastPersistedProcessed = -1
    private var lastPersistedScanId = ""

    init {
        persist(mutable.value, force = true)
    }

    fun snapshot(): ManualScanSession = mutable.value

    fun begin(timeframeMinutes: Int): ManualScanSession = set(
        ManualScanSession(
            scanId = UUID.randomUUID().toString(),
            status = ManualScanStatus.PREFLIGHT,
            phase = ScanPhase.STARTING.name,
            analysisTimeframeMinutes = timeframeMinutes,
            startedAt = System.currentTimeMillis(),
            lastUpdate = System.currentTimeMillis(),
            providerStatus = "DOĞRULANIYOR",
            dataQuality = ManualDataQuality.UNKNOWN,
            message = "Veri sağlayıcısı ve BIST sembol evreni doğrulanıyor."
        ),
        forcePersist = true
    )

    fun setPreflight(total: Int, providerStatus: String, scanMode: BistScanMode, message: String): ManualScanSession = update({
        it.copy(
            status = ManualScanStatus.PREFLIGHT,
            phase = ScanPhase.LOADING_DATA.name,
            totalCount = total.coerceAtLeast(0),
            providerStatus = providerStatus,
            scanMode = scanMode.name,
            dataQuality = if (scanMode == BistScanMode.REALTIME_ONLY) ManualDataQuality.READY else ManualDataQuality.STALE,
            lastUpdate = System.currentTimeMillis(),
            message = message
        )
    }, forcePersist = true)

    fun updateFromScan(
        state: ScanState,
        providerStatus: String,
        publishCompletion: Boolean = true
    ): ManualScanSession = update(transform = { previous ->
        val runStatus = state.scanRun?.status
        val mappedStatus = when (state.status) {
            ScanStatus.IDLE -> if (state.phase == ScanPhase.READY) ManualScanStatus.PREFLIGHT else ManualScanStatus.IDLE
            ScanStatus.RUNNING -> if (ManualScanPauseGate.isPaused()) ManualScanStatus.PAUSED else ManualScanStatus.RUNNING
            ScanStatus.CANCELLED -> ManualScanStatus.STOPPED
            ScanStatus.ERROR -> if (state.phase == ScanPhase.NO_DATA) ManualScanStatus.DATA_UNAVAILABLE else ManualScanStatus.ERROR
            ScanStatus.COMPLETED -> when {
                !publishCompletion -> ManualScanStatus.FINALIZING
                runStatus == ScanRunStatus.COMPLETE -> ManualScanStatus.COMPLETED
                runStatus == ScanRunStatus.PARTIAL -> ManualScanStatus.PARTIAL
                state.results.isEmpty() -> ManualScanStatus.DATA_UNAVAILABLE
                else -> ManualScanStatus.PARTIAL
            }
        }
        val longCount = state.results.count { it.direction.equals("LONG", true) }
        val shortCount = state.results.count { it.direction.equals("SHORT", true) }
        val watchCount = state.results.size - longCount - shortCount
        val quality = when {
            mappedStatus == ManualScanStatus.DATA_UNAVAILABLE -> ManualDataQuality.NO_DATA
            mappedStatus == ManualScanStatus.ERROR -> ManualDataQuality.ERROR
            mappedStatus == ManualScanStatus.FINALIZING -> previous.dataQuality
            mappedStatus == ManualScanStatus.PARTIAL || runStatus == ScanRunStatus.PARTIAL -> ManualDataQuality.PARTIAL
            state.scanMode != BistScanMode.REALTIME_ONLY -> ManualDataQuality.STALE
            mappedStatus == ManualScanStatus.COMPLETED -> ManualDataQuality.READY
            else -> previous.dataQuality
        }
        val now = System.currentTimeMillis()
        previous.copy(
            status = mappedStatus,
            phase = if (mappedStatus == ManualScanStatus.FINALIZING) "PERSISTING_RESULTS" else state.phase.name,
            scannedCount = state.processed.coerceAtLeast(0),
            totalCount = state.total.coerceAtLeast(previous.totalCount),
            longCount = longCount,
            shortCount = shortCount,
            watchCount = watchCount.coerceAtLeast(0),
            currentSymbol = state.currentSymbol ?: previous.currentSymbol,
            providerStatus = providerStatus.ifBlank { previous.providerStatus },
            dataQuality = quality,
            scanMode = state.scanMode.name,
            lastUpdate = now,
            completedAt = if (mappedStatus in TERMINAL) now else null,
            resultCount = state.results.size,
            runStatus = runStatus?.name,
            message = when {
                mappedStatus == ManualScanStatus.FINALIZING -> "Tarama sonuçları atomik olarak kaydediliyor; COMPLETED henüz yayımlanmadı."
                else -> state.errorMessage ?: scanMessage(state)
            }
        )
    })

    fun markUnavailable(message: String, providerStatus: String = "HAZIR DEĞİL"): ManualScanSession = update({
        it.copy(
            status = ManualScanStatus.DATA_UNAVAILABLE,
            phase = ScanPhase.NO_DATA.name,
            providerStatus = providerStatus,
            dataQuality = ManualDataQuality.NO_DATA,
            lastUpdate = System.currentTimeMillis(),
            completedAt = System.currentTimeMillis(),
            message = message
        )
    }, forcePersist = true)

    fun markNetworkState(available: Boolean): ManualScanSession = update(transform = {
        if (!it.isActive) it else it.copy(
            providerStatus = if (available) "AĞ GERİ GELDİ • provider retry politikası aktif" else "AĞ YOK • aktif istek sonucu bekleniyor",
            dataQuality = if (available) it.dataQuality else ManualDataQuality.PARTIAL,
            lastUpdate = System.currentTimeMillis(),
            message = if (available) it.message else "Ağ bağlantısı kesildi; sahte progress/veri üretilmiyor."
        )
    })

    fun markPaused(message: String = "Ağ bağlantısı yok; tarama yeni provider işi başlatmadan duraklatıldı."): ManualScanSession = update({
        if (!it.isActive) it else it.copy(
            status = ManualScanStatus.PAUSED,
            providerStatus = "AĞ YOK • TARAMA DURAKLATILDI",
            dataQuality = ManualDataQuality.PARTIAL,
            lastUpdate = System.currentTimeMillis(),
            message = message
        )
    }, forcePersist = true)

    fun markResumedFromPause(): ManualScanSession = update({
        if (it.status != ManualScanStatus.PAUSED) it else it.copy(
            status = ManualScanStatus.RUNNING,
            providerStatus = "AĞ GERİ GELDİ • TARAMA DEVAM EDİYOR",
            lastUpdate = System.currentTimeMillis(),
            message = "Ağ bağlantısı geri geldi; tarama kaldığı iş akışından devam ediyor."
        )
    }, forcePersist = true)

    fun markStopped(message: String = "Tarama kullanıcı tarafından durduruldu."): ManualScanSession = update({
        it.copy(
            status = ManualScanStatus.STOPPED,
            phase = ScanPhase.STOPPED.name,
            lastUpdate = System.currentTimeMillis(),
            completedAt = System.currentTimeMillis(),
            message = message
        )
    }, forcePersist = true)

    fun markError(message: String): ManualScanSession = update({
        it.copy(
            status = ManualScanStatus.ERROR,
            phase = ScanPhase.ERROR.name,
            dataQuality = ManualDataQuality.ERROR,
            lastUpdate = System.currentTimeMillis(),
            completedAt = System.currentTimeMillis(),
            message = message
        )
    }, forcePersist = true)

    private fun set(session: ManualScanSession, forcePersist: Boolean = false): ManualScanSession = synchronized(lock) {
        mutable.value = session
        persist(session, forcePersist)
        session
    }

    private fun update(
        transform: (ManualScanSession) -> ManualScanSession,
        forcePersist: Boolean = false
    ): ManualScanSession = synchronized(lock) {
        set(transform(mutable.value), forcePersist)
    }

    private fun persist(session: ManualScanSession, force: Boolean) {
        val now = System.currentTimeMillis()
        val terminal = session.status in TERMINAL
        val due = ManualScanPersistencePolicy.shouldPersist(
            force = force || terminal,
            scanIdChanged = session.scanId != lastPersistedScanId,
            nowMs = now,
            lastPersistAtMs = lastPersistAt,
            processed = session.scannedCount,
            lastPersistedProcessed = lastPersistedProcessed
        )
        if (!due) return
        val synchronous = force || terminal
        check(store.save(session, synchronous)) { "Manual scan session kalıcı kaydı başarısız." }
        lastPersistAt = now
        lastPersistedProcessed = session.scannedCount
        lastPersistedScanId = session.scanId
    }


    private fun scanMessage(state: ScanState): String = when (state.phase) {
        ScanPhase.READY -> "Tarama hazır."
        ScanPhase.STARTING -> "Tarama başlatılıyor."
        ScanPhase.LOADING_SYMBOLS -> "BIST sembol evreni yükleniyor."
        ScanPhase.LOADING_DATA -> "OHLCV verileri hazırlanıyor."
        ScanPhase.SCANNING -> "BIST hisseleri gerçek veriyle taranıyor."
        ScanPhase.CALCULATING -> "Teknik göstergeler hesaplanıyor."
        ScanPhase.RANKING -> "LONG/SHORT/İZLE sonuçları sıralanıyor."
        ScanPhase.COMPLETED -> "Tarama tamamlandı."
        ScanPhase.NO_DATA -> "Seçilen periyot için veri kullanılamıyor."
        ScanPhase.STOPPED -> "Tarama durduruldu."
        ScanPhase.ERROR -> state.errorMessage ?: "Tarama hatası."
        ScanPhase.IDLE -> "Hazır."
    }

    companion object {
        private val TERMINAL = setOf(
            ManualScanStatus.COMPLETED,
            ManualScanStatus.PARTIAL,
            ManualScanStatus.STOPPED,
            ManualScanStatus.ERROR,
            ManualScanStatus.DATA_UNAVAILABLE,
            ManualScanStatus.INTERRUPTED
        )
        @Volatile private var instance: ManualScanSessionRepository? = null

        fun get(context: Context): ManualScanSessionRepository = instance ?: synchronized(this) {
            instance ?: ManualScanSessionRepository(context.applicationContext).also { instance = it }
        }
    }
}
