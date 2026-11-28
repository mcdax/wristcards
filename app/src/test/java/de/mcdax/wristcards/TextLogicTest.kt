package de.mcdax.wristcards

import org.junit.Assert.assertEquals
import org.junit.Test

class TextLogicTest {
    @Test fun `server address gets completed`() {
        assertEquals("", normalizeEndpoint("  "))
        assertEquals("https://anki.example.de/", normalizeEndpoint("anki.example.de"))
        assertEquals("http://192.168.1.2:8080/", normalizeEndpoint("http://192.168.1.2:8080"))
        assertEquals("https://x.de/", normalizeEndpoint("https://x.de/"))
    }

    /** Applies [inlineBullets] like toAnnotated, only as plain text. */
    private fun bullets(raw: String): String {
        val marks = inlineBullets(raw)
        val out = StringBuilder()
        var skip = 0
        for (i in raw.indices) {
            if (skip > 0) { skip--; continue }
            when (marks[i]) {
                Bullet.SEPARATOR -> { out.append("\n• "); skip = 2; continue }
                Bullet.AFTER_COLON -> { out.append("\n• "); continue }
                Bullet.BEFORE -> out.append("• ")
                null -> Unit
            }
            out.append(raw[i])
        }
        return out.toString()
    }

    @Test fun `middle-dot list after colon`() {
        assertEquals("Nenne:\n• Rot\n• Grün\n• Blau", bullets("Nenne: Rot · Grün · Blau"))
    }

    @Test fun `middle-dot list without intro`() {
        assertEquals("• Rot\n• Blau", bullets("Rot · Blau"))
    }

    @Test fun `multiplication dot in a formula stays`() {
        assertEquals("F = m · a", bullets("F = m · a"))
        assertEquals("Fläche: a · b", bullets("Fläche: a · b"))
    }

    @Test fun `line that is already a list item is not split again`() {
        assertEquals("• F = m · a", bullets("• F = m · a"))
        assertEquals("• Rot · Blau", bullets("• Rot · Blau"))
    }

    @Test fun `text without middle dot stays`() {
        assertEquals("Hallo: Welt", bullets("Hallo: Welt"))
    }

    @Test fun `answer side starts after hr id=answer`() {
        assertEquals("Antwort", answerPart("Frage<hr id=answer>Antwort"))
        assertEquals("ohne", answerPart("ohne"))
    }
}
