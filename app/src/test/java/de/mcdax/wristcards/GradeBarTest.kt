package de.mcdax.wristcards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Four rating buttons on a flat arc (outer ones raised 10 dp, bottom padding 14 dp). */
class GradeBarTest {
    private fun four(screen: Int) = gradeButtonWidth(screen, 4, 2f, 44f, 24f)

    @Test fun `reference watch`() {
        assertEquals(36.4f, four(227), 0.3f)
    }

    @Test fun `buttons get narrower on smaller watches but keep a floor`() {
        val widths = listOf(227, 198, 192, 183, 160, 120).map(::four)
        assertTrue("monotonic: $widths", widths.zipWithNext().all { (a, b) -> a >= b })
        assertTrue("192 dp: ${four(192)}", four(192) in 31f..35f)
        assertTrue("floor: $widths", widths.all { it >= 24f })
    }

    @Test fun `large displays are capped at the maximum width`() {
        assertEquals(44f, four(400), 0.01f)
    }
}
