package de.mcdax.ankiwear

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Was die Deckliste über den Abgleich anzeigt. */
sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data object Done : SyncState
    data class Failed(val message: String) : SyncState
    /** Vollabgleich nötig, der die Sammlung der Uhr ersetzen würde: erst nach Bestätigung. */
    data object NeedsConfirmation : SyncState
}

/**
 * Startet Abgleiche unabhängig von der Oberfläche: Sie laufen im Prozess weiter, auch wenn Wear OS
 * die Activity neu aufbaut, und es läuft nie mehr als einer gleichzeitig.
 */
class SyncController private constructor(private val repo: AnkiRepository) {
    companion object {
        @Volatile private var instance: SyncController? = null

        fun get(context: Context): SyncController =
            instance ?: synchronized(this) {
                instance ?: SyncController(AnkiRepository.get(context)).also { instance = it }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> get() = _state

    /** [confirmFullDownload]: Der Nutzer hat bestätigt, dass die Sammlung der Uhr ersetzt werden darf. */
    fun sync(confirmFullDownload: Boolean = false) {
        synchronized(this) {
            if (_state.value == SyncState.Running) return
            _state.value = SyncState.Running
        }
        scope.launch {
            _state.value = when (val r = repo.sync(confirmFullDownload)) {
                SyncResult.Ok -> SyncState.Done
                SyncResult.NeedsFullDownload -> SyncState.NeedsConfirmation
                is SyncResult.Failed -> SyncState.Failed(r.message)
            }
            // Zahlen nach dem Abgleich; ein Fehler beim Neuladen soll den Abgleich-Status nicht überschreiben.
            runCatching { repo.decks() }
        }
    }

    /** Rückfrage verneint: nichts ersetzen, beim nächsten Abgleich wird erneut gefragt. */
    fun dismissConfirmation() {
        _state.compareAndSet(SyncState.NeedsConfirmation, SyncState.Idle)
    }
}
