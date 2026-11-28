package de.mcdax.wristcards

import org.junit.Assert.assertEquals
import org.junit.Test

/** Card text placement on a 200 high screen with 36 at the top and 60 kept free at the bottom. */
class CenteringTest {
    private fun place(content: Float) = centeredContent(content, 200f, 36f, 60f)

    @Test fun `short content sits in the middle of the screen`() {
        assertEquals(90f to 200f, place(20f))
    }

    @Test fun `growing content moves up to keep the bottom free`() {
        // Centered (top 50) it would end at 150, inside the 60 kept free; it moves up to end at 140.
        assertEquals(40f to 200f, place(100f))
    }

    @Test fun `long content starts at the top and scrolls`() {
        assertEquals(36f to 36f + 300f + 60f, place(300f))
    }
}
