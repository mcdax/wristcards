package de.mcdax.wristcards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeBarTest {
    private fun four(screen: Int) = gradeButtonWidth(screen, 4, 2f, 44f, 38f)

    @Test fun `full width on the reference watch`() {
        assertEquals(44f, four(227), 0.2f)
    }

    @Test fun `buttons get narrower on smaller watches but stay usable`() {
        val sizes = listOf(227, 198, 192, 183, 160)
        val widths = sizes.map(::four)
        assertTrue("monotonic: $widths", widths.zipWithNext().all { (a, b) -> a >= b })
        assertTrue("192 dp: ${four(192)}", four(192) in 36f..42f)
        assertTrue("never below the floor: $widths", widths.all { it >= 24f })
    }
}
