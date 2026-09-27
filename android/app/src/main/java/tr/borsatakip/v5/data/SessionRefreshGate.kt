package tr.borsatakip.v5.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide refresh single-flight primitive. */
class SessionRefreshGate {
    private val mutex = Mutex()

    suspend fun run(
        isSatisfied: () -> Boolean,
        refresh: suspend () -> Boolean
    ): Boolean = mutex.withLock {
        if (isSatisfied()) true else refresh()
    }
}
