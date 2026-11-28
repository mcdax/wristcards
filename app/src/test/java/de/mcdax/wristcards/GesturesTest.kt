package de.mcdax.wristcards

import de.mcdax.wristcards.Gestures.Axis
import de.mcdax.wristcards.Gestures.Left
import de.mcdax.wristcards.Gestures.Right
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

/** Grid of gesture decisions on a 454 px wide round watch (density 2: 50 dp = 100 px, 80 dp = 160 px). */
class GesturesTest {
    private val width = 454
    private val rightMin = 100f
    private val leftMin = 160f
    private val slop = 16f
    private val noRating = 10_000L

    private fun right(x: Float, revealed: Boolean, hasCard: Boolean = true, rateRight: Boolean = true, since: Long = noRating) =
        Gestures.rightSwipe(x, width, hasCard, revealed, rateRight, since)

    @Test fun `right swipe from the edge zone covers a revealed answer, further inside it rates`() {
        // Edge zone ends at 20 % of the width = 90.8 px.
        for (x in listOf(0f, 5f, 45f, 85f, 90f)) assertEquals("x=$x", Right.HIDE, right(x, revealed = true))
        for (x in listOf(91f, 97f, 130f, 230f, 300f, 440f)) assertEquals("x=$x", Right.RATE, right(x, revealed = true))
    }

    @Test fun `right swipe on a hidden answer goes back from the edge and reveals from inside`() {
        for (x in listOf(5f, 45f, 85f)) assertEquals("x=$x", Right.BACK, right(x, revealed = false))
        for (x in listOf(97f, 120f, 230f, 300f)) assertEquals("x=$x", Right.REVEAL, right(x, revealed = false))
    }

    @Test fun `without right-swipe rating a revealed answer is covered from anywhere`() {
        for (x in listOf(5f, 97f, 230f)) assertEquals("x=$x", Right.HIDE, right(x, revealed = true, rateRight = false))
    }

    @Test fun `without a card every right swipe goes back`() {
        for (x in listOf(5f, 230f)) for (revealed in listOf(true, false)) {
            assertEquals(Right.BACK, right(x, revealed, hasCard = false))
        }
    }

    @Test fun `right swipes within the rating lock are ignored, also from the edge`() {
        for (since in listOf(0L, 80L, 200L, RATE_LOCK_MS - 1)) for (x in listOf(5f, 230f)) {
            assertEquals("since=$since x=$x", Right.LOCKED, right(x, revealed = false, since = since))
        }
        assertEquals(Right.BACK, right(5f, revealed = false, since = RATE_LOCK_MS))
    }

    @Test fun `right swipe needs distance and must be clearly horizontal`() {
        assertTrue(Gestures.isRightSwipe(260f, 0f, rightMin))
        assertTrue(Gestures.isRightSwipe(101f, 0f, rightMin))
        assertFalse(Gestures.isRightSwipe(100f, 0f, rightMin))
        assertFalse(Gestures.isRightSwipe(25f, 0f, rightMin))
        assertFalse(Gestures.isRightSwipe(-260f, 0f, rightMin))
        // |dy| must stay below dx/2: 20° passes, 30° and steeper do not.
        assertTrue(Gestures.isRightSwipe(220f, 80f, rightMin))
        assertTrue(Gestures.isRightSwipe(200f, -99f, rightMin))
        assertFalse(Gestures.isRightSwipe(200f, 100f, rightMin))
        assertFalse(Gestures.isRightSwipe(200f, -150f, rightMin))
    }

    private fun angle(deg: Double, length: Float): Pair<Float, Float> {
        val dx = length / kotlin.math.sqrt(1 + tan(Math.toRadians(deg)) * tan(Math.toRadians(deg))).toFloat()
        return dx to (dx * tan(Math.toRadians(deg))).toFloat()
    }

    @Test fun `card gestures become horizontal only below about 26 degrees`() {
        for (deg in listOf(0.0, 10.0, 20.0, 25.0)) {
            val (dx, dy) = angle(deg, 60f)
            assertEquals("$deg°", Axis.HORIZONTAL, Gestures.axis(-dx, -dy, slop))
        }
        for (deg in listOf(30.0, 40.0, 60.0, 90.0)) {
            val (dx, dy) = angle(deg, 60f)
            assertEquals("$deg°", Axis.VERTICAL, Gestures.axis(-dx, -dy, slop))
        }
    }

    @Test fun `small movements stay undecided`() {
        assertNull(Gestures.axis(10f, 5f, slop))
        assertNull(Gestures.axis(-16f, 0f, slop))
        assertNull(Gestures.axis(0f, 16f, slop))
    }

    @Test fun `thumb arc scrolling is decided as vertical before it could turn into a left swipe`() {
        // Same points as the emulator test: DOWN 330,340 then an arc up and to the left.
        val points = listOf(320f to 300f, 305f to 260f, 290f to 220f, 270f to 190f, 245f to 160f, 215f to 140f)
        var decided: Axis? = null
        for ((x, y) in points) {
            decided = Gestures.axis(x - 330f, y - 340f, slop)
            if (decided != null) break
        }
        assertEquals(Axis.VERTICAL, decided)
    }

    @Test fun `fast left swipe is recognized from down and up point alone`() {
        assertTrue(Gestures.isLeftSwipe(-280f, 0f, leftMin))
        assertTrue(Gestures.isLeftSwipe(-200f, 99f, leftMin))
        assertFalse(Gestures.isLeftSwipe(-160f, 0f, leftMin))
        assertFalse(Gestures.isLeftSwipe(-200f, 100f, leftMin))
        assertFalse(Gestures.isLeftSwipe(280f, 0f, leftMin))
    }

    @Test fun `left swipe rates a revealed answer and reveals a hidden one`() {
        assertEquals(Left.RATE, Gestures.leftSwipe(-280f, leftMin, revealed = true, sinceRatingMs = noRating))
        assertEquals(Left.REVEAL, Gestures.leftSwipe(-280f, leftMin, revealed = false, sinceRatingMs = noRating))
        assertEquals(Left.NONE, Gestures.leftSwipe(-160f, leftMin, revealed = true, sinceRatingMs = noRating))
        assertEquals(Left.NONE, Gestures.leftSwipe(-25f, leftMin, revealed = false, sinceRatingMs = noRating))
        assertEquals(Left.NONE, Gestures.leftSwipe(280f, leftMin, revealed = true, sinceRatingMs = noRating))
    }

    @Test fun `left swipe right after a rating does not reveal the next card unread`() {
        assertEquals(Left.NONE, Gestures.leftSwipe(-280f, leftMin, revealed = false, sinceRatingMs = 100))
        assertEquals(Left.NONE, Gestures.leftSwipe(-280f, leftMin, revealed = false, sinceRatingMs = RATE_LOCK_MS - 1))
        assertEquals(Left.REVEAL, Gestures.leftSwipe(-280f, leftMin, revealed = false, sinceRatingMs = RATE_LOCK_MS))
    }

    @Test fun `a cancelled back swipe counts only if it went far and was not pulled back`() {
        assertTrue(Gestures.cancelledBackCounts(200f, 440f, 440f, rightMin))
        assertTrue(Gestures.cancelledBackCounts(200f, 320f, 340f, rightMin))
        // Pulled back by more than a quarter of the minimum distance.
        assertFalse(Gestures.cancelledBackCounts(200f, 310f, 350f, rightMin))
        // Pulled back almost to the start.
        assertFalse(Gestures.cancelledBackCounts(200f, 205f, 350f, rightMin))
        // Not far enough.
        assertFalse(Gestures.cancelledBackCounts(200f, 290f, 290f, rightMin))
    }
}
