package de.mcdax.wristcards

import kotlin.math.abs

/**
 * Gesture decisions of the study view as pure functions, free of Android and Compose types, so the
 * whole grid (start points, angles, distances, rating lock) is covered by JVM tests. The emulator
 * end-to-end test only checks what needs the real system: delivery of fast swipes, interception of
 * the back gesture, keys and timing.
 */
internal object Gestures {
    enum class Axis { HORIZONTAL, VERTICAL }

    enum class Right { BACK, RATE, HIDE, REVEAL, LOCKED }

    enum class Left { RATE, REVEAL, NONE }

    /**
     * Direction of a card gesture so far, or null while undecided. Horizontal only once the movement is
     * clearly horizontal (|dy| < |dx|/2): on the round watch the thumb moves in an arc, and diagonal
     * scrolling must stay scrolling.
     */
    fun axis(dx: Float, dy: Float, slop: Float): Axis? = when {
        abs(dx) > slop && abs(dy) < abs(dx) / 2 -> Axis.HORIZONTAL
        abs(dy) > slop -> Axis.VERTICAL
        else -> null
    }

    /**
     * A right swipe judged from down and up point alone. Very fast swipes arrive without intermediate
     * points, so this is checked at activity level instead of in the card.
     */
    fun isRightSwipe(dx: Float, dy: Float, minDistance: Float) = dx > minDistance && abs(dy) < dx / 2

    /**
     * What a right swipe does. Within [RATE_LOCK_MS] after a rating it still belongs to that rating.
     * Hidden answer: edge = back to the deck list, further inside = reveal. Revealed: from the center the
     * right-swipe rating applies (if set); otherwise the swipe covers the answer again.
     */
    fun rightSwipe(
        downX: Float,
        width: Int,
        hasCard: Boolean,
        revealed: Boolean,
        rateRightEnabled: Boolean,
        sinceRatingMs: Long,
    ): Right {
        if (sinceRatingMs < RATE_LOCK_MS) return Right.LOCKED
        val edge = downX < width * BACK_ZONE
        return when {
            !hasCard -> Right.BACK
            revealed && !edge && rateRightEnabled -> Right.RATE
            revealed -> Right.HIDE
            edge -> Right.BACK
            else -> Right.REVEAL
        }
    }

    /**
     * What a horizontal card gesture to the left does. Revealed: rate. Hidden: reveal, but not right
     * after a rating, otherwise a double swipe would reveal the new card unread.
     */
    fun leftSwipe(dx: Float, minDistance: Float, revealed: Boolean, sinceRatingMs: Long): Left = when {
        dx >= -minDistance -> Left.NONE
        revealed -> Left.RATE
        sinceRatingMs >= RATE_LOCK_MS -> Left.REVEAL
        else -> Left.NONE
    }

    /**
     * The system sometimes cancels a fast back swipe; it still counts if it went far enough and the
     * finger was not pulled back noticeably.
     */
    fun cancelledBackCounts(downX: Float, lastX: Float, maxX: Float, minDistance: Float) =
        lastX - downX > minDistance && maxX - lastX < minDistance / 4
}
