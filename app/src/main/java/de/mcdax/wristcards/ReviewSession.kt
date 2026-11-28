package de.mcdax.wristcards

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

/** What can go wrong while studying; the UI picks the text for it. */
internal sealed interface ReviewError {
    /** Rating not saved: the card stays. */
    data object RateFailed : ReviewError
    data object UndoFailed : ReviewError
    /** Card not loaded (also after a saved rating): "Try again" only reloads. */
    data class LoadFailed(val message: String?) : ReviewError
}

/**
 * Study flow of a deck without UI: current card, revealed, rate, undo, errors.
 * Gestures and rendering stay in [ReviewScreen]; they only call [reveal], [rate] and [undo].
 * [revealedState] and [studiedState] come from rememberSaveable so they survive a rebuild.
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
    /** Only after the first load; before that the view shows nothing instead of "Done". */
    var loaded by mutableStateOf(false)
        private set
    var revealed by revealedState
    /** Rating, reloading or undo is running; buttons are locked then. */
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<ReviewError?>(null)
        private set
    /** Cards rated in this session, for the summary. */
    var studied by studiedState
        private set
    /** Time of the last rating (uptimeMillis), for the lock against double swipes. */
    var ratedAt by mutableLongStateOf(0L)
        private set

    private var revealsSeen by mutableStateOf(hints.getInt(KEY_REVEALS, 0))
    /** Hint "Tap for the answer" for the very first reveals. */
    val showRevealHint get() = !revealed && revealsSeen < REVEAL_HINT_COUNT

    /** Loads the first (or after an error the next) card. */
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
            android.util.Log.w(TAG, "Card could not be loaded", e)
            error = ReviewError.LoadFailed(e.message)
            null
        }
        loaded = true
    }

    fun retryLoad() {
        scope.launch { load() }
    }

    /** Covers the answer again and returns to the question (back swipe on a revealed card). */
    fun hide() {
        revealed = false
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
     * Rates [current] with index [idx] (Again, Hard, Good, Easy). [onRated] only comes after the
     * rating is saved (overlay, vibration), [onDeckDone] when nothing is due afterwards.
     */
    fun rate(current: ReviewCard, idx: Int, onRated: () -> Unit, onDeckDone: () -> Unit) {
        if (busy) return
        busy = true
        // Set before starting: the swipe lock must already apply to the gesture that is rating right now.
        ratedAt = android.os.SystemClock.uptimeMillis()
        scope.launch {
            try {
                // Preview rates nothing.
                if (previewCard != null) return@launch
                // The native write access is not cancellable. Commit and removal
                // of the old card belong together, even if the view is closed.
                try {
                    withContext(NonCancellable) {
                        repo.answer(current, GRADES[idx])
                        card = null
                        revealed = false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Rating failed", e)
                    error = ReviewError.RateFailed
                    return@launch
                }
                studied++
                onRated()
                // The rating is saved now; if only reloading fails, only that is repeated.
                try {
                    card = repo.nextCard()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Next card could not be loaded", e)
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

    /** Reverts the rating of [cardId] and shows the card again with the answer. */
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
                android.util.Log.w(TAG, "Undo failed", e)
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

/** Order like the core's intervals: Again, Hard, Good, Easy. */
private val GRADES = listOf(CardAnswer.Rating.AGAIN, CardAnswer.Rating.HARD, CardAnswer.Rating.GOOD, CardAnswer.Rating.EASY)

/** For up to this many reveals, the question shows the hint "Tap to reveal". */
private const val REVEAL_HINT_COUNT = 3
