package de.mcdax.ankiwear

import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import anki.scheduler.CardAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Was beim Lernen schiefgehen kann; die Oberfläche wählt den Text dazu. */
internal sealed interface ReviewError {
    /** Bewertung nicht gespeichert: Karte bleibt stehen. */
    data object RateFailed : ReviewError
    data object UndoFailed : ReviewError
    /** Karte nicht geladen (auch nach gespeicherter Bewertung): „Erneut versuchen“ lädt nur neu. */
    data class LoadFailed(val message: String?) : ReviewError
}

/**
 * Lernablauf eines Decks ohne Oberfläche: aktuelle Karte, aufgedeckt, Bewerten, Rückgängig, Fehler.
 * Gesten und Darstellung bleiben in [ReviewScreen]; sie rufen nur [reveal], [rate] und [undo].
 * [revealedState] und [studiedState] kommen aus rememberSaveable, damit sie einen Neuaufbau überstehen.
 */
@Stable
internal class ReviewSession(
    private val repo: AnkiRepository,
    private val deckId: Long?,
    private val previewCard: Long?,
    private val scope: CoroutineScope,
    private val hints: SharedPreferences,
    revealedState: MutableState<Boolean>,
    studiedState: MutableState<Int>,
) {
    var card by mutableStateOf<ReviewCard?>(null)
        private set
    /** Erst nach dem ersten Laden; vorher zeigt die Ansicht nichts statt „Fertig“. */
    var loaded by mutableStateOf(false)
        private set
    var revealed by revealedState
    /** Bewerten, Nachladen oder Rückgängig läuft; Knöpfe sind dann gesperrt. */
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<ReviewError?>(null)
        private set
    /** In dieser Sitzung bewertete Karten, für den Abschluss. */
    var studied by studiedState
        private set
    /** Zeitpunkt der letzten Bewertung (uptimeMillis), für die Sperre gegen Doppelwischen. */
    var ratedAt by mutableLongStateOf(0L)
        private set

    private var revealsSeen by mutableStateOf(hints.getInt(KEY_REVEALS, 0))
    /** Hinweis „Tippen für die Antwort“ für die ersten Aufdeckungen überhaupt. */
    val showRevealHint get() = !revealed && revealsSeen < REVEAL_HINT_COUNT

    /** Lädt die erste (bzw. nach einem Fehler die nächste) Karte. */
    suspend fun load() {
        error = null
        card = try {
            if (previewCard != null) {
                repo.cardById(previewCard)
            } else {
                deckId?.let { repo.selectDeck(it) }
                repo.nextCard()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Karte konnte nicht geladen werden", e)
            error = ReviewError.LoadFailed(e.message)
            null
        }
        loaded = true
    }

    fun retryLoad() {
        scope.launch { load() }
    }

    fun reveal() {
        if (revealed) return
        revealed = true
        if (revealsSeen < REVEAL_HINT_COUNT) {
            revealsSeen++
            hints.edit().putInt(KEY_REVEALS, revealsSeen).apply()
        }
    }

    /**
     * Bewertet [current] mit Index [idx] (Nochmal, Schwer, Gut, Einfach). [onRated] kommt nur nach
     * gespeicherter Bewertung (Einblendung, Vibration), [onDeckDone], wenn danach nichts mehr fällig ist.
     */
    fun rate(current: ReviewCard, idx: Int, onRated: () -> Unit, onDeckDone: () -> Unit) {
        if (busy) return
        busy = true
        // Vor dem Start setzen: Die Wischsperre muss schon für die Geste gelten, die gerade bewertet.
        ratedAt = android.os.SystemClock.uptimeMillis()
        scope.launch {
            try {
                // Vorschau bewertet nichts.
                if (previewCard != null) return@launch
                // Der native Schreibzugriff ist nicht abbrechbar. Commit und Entfernen
                // der alten Karte gehören zusammen, auch wenn die Ansicht geschlossen wird.
                try {
                    withContext(NonCancellable) {
                        repo.answer(current, GRADES[idx])
                        card = null
                        revealed = false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Bewerten fehlgeschlagen", e)
                    error = ReviewError.RateFailed
                    return@launch
                }
                studied++
                onRated()
                // Gespeichert ist die Bewertung jetzt; scheitert nur das Nachladen, wird nur das wiederholt.
                try {
                    card = repo.nextCard()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Nächste Karte konnte nicht geladen werden", e)
                    error = ReviewError.LoadFailed(e.message)
                    return@launch
                }
                if (card == null) onDeckDone()
                revealed = false
                error = null
            } finally {
                busy = false
            }
        }
    }

    /** Nimmt die Bewertung von [cardId] zurück und zeigt die Karte wieder mit Antwort. */
    fun undo(cardId: Long) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                card = withContext(NonCancellable) { repo.undoAnswer(cardId) }
                revealed = true
                studied = (studied - 1).coerceAtLeast(0)
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Rückgängig fehlgeschlagen", e)
                error = ReviewError.UndoFailed
            } finally {
                busy = false
            }
        }
    }

    private companion object {
        const val KEY_REVEALS = "revealsSeen"
    }
}

/** Reihenfolge wie die Intervalle des Kerns: Nochmal, Schwer, Gut, Einfach. */
private val GRADES = listOf(CardAnswer.Rating.AGAIN, CardAnswer.Rating.HARD, CardAnswer.Rating.GOOD, CardAnswer.Rating.EASY)

/** Bis zu so vielen Aufdeckungen zeigt die Frage den Hinweis „Tippen zum Aufdecken“. */
private const val REVEAL_HINT_COUNT = 15
