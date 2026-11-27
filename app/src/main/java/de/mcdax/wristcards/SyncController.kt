package de.mcdax.wristcards

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the deck list shows about the sync. */
sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data object Done : SyncState
    data class Failed(val message: String) : SyncState
    /** Full sync needed that would replace the watch's collection: only after confirmation. */
    data object NeedsConfirmation : SyncState
}

/**
 * Starts syncs independently of the UI: they keep running in the process even if Wear OS
 * rebuilds the activity, and never more than one runs at a time.
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

    /** [resolve]: on a conflict the side confirmed by the user; otherwise null. */
    fun sync(resolve: ConflictChoice? = null) {
        synchronized(this) {
            if (_state.value == SyncState.Running) return
            _state.value = SyncState.Running
        }
        scope.launch {
            _state.value = when (val r = repo.sync(resolve)) {
                SyncResult.Ok -> SyncState.Done
                SyncResult.NeedsFullDownload -> SyncState.NeedsConfirmation
                is SyncResult.Failed -> SyncState.Failed(r.message)
            }
            // Numbers after the sync; an error while reloading must not overwrite the sync status.
            runCatching { repo.decks() }
        }
    }

    /** Prompt declined: replace nothing, the next sync asks again. */
    fun dismissConfirmation() {
        _state.compareAndSet(SyncState.NeedsConfirmation, SyncState.Idle)
    }
}
