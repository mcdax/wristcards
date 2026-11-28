package de.mcdax.wristcards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Four rating buttons on an arc whose height follows the display size (bottom padding 14 dp). */
class GradeBarTest {
    private fun four(screen: Int) = gradeButtonWidth(screen, 4, 2f, 44f, 14f + gradeBarRaise(screen))

    @Test fun `arc is flat on small watches and rises on large ones`() {
        assertEquals(10f, gradeBarRaise(160), 0.01f)
        assertEquals(10f, gradeBarRaise(192), 0.01f)
        assertEquals(18f, gradeBarRaise(227), 0.01f)
        assertEquals(18f, gradeBarRaise(260), 0.01f)
    }

    @Test fun `buttons are wider on the reference watch than on small ones`() {
        assertEquals(41f, four(227), 1f)
        assertTrue("192 dp: ${four(192)}", four(192) in 31f..35f)
    }

    @Test fun `width shrinks with the display and keeps a floor`() {
        val widths = listOf(260, 227, 198, 192, 183, 160, 120).map(::four)
        assertTrue("monotonic: $widths", widths.zipWithNext().all { (a, b) -> a >= b })
        assertTrue("floor: $widths", widths.all { it >= 24f })
    }
}
