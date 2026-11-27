package de.mcdax.ankiwear

import org.junit.Assert.assertEquals
import org.junit.Test

class TextLogicTest {
    @Test fun `Server-Adresse wird ergänzt`() {
        assertEquals("", normalizeEndpoint("  "))
        assertEquals("https://anki.example.de/", normalizeEndpoint("anki.example.de"))
        assertEquals("http://192.168.1.2:8080/", normalizeEndpoint("http://192.168.1.2:8080"))
        assertEquals("https://x.de/", normalizeEndpoint("https://x.de/"))
    }

    /** Wendet [inlineBullets] an wie toAnnotated, nur als einfacher Text. */
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

    @Test fun `Mittelpunkt-Liste nach Doppelpunkt`() {
        assertEquals("Nenne:\n• A\n• B\n• C", bullets("Nenne: A · B · C"))
    }

    @Test fun `Mittelpunkt-Liste ohne Einleitung`() {
        assertEquals("• A\n• B", bullets("A · B"))
    }

    @Test fun `Text ohne Mittelpunkt bleibt`() {
        assertEquals("Hallo: Welt", bullets("Hallo: Welt"))
    }

    @Test fun `Antwortseite beginnt nach hr id=answer`() {
        assertEquals("Antwort", answerPart("Frage<hr id=answer>Antwort"))
        assertEquals("ohne", answerPart("ohne"))
    }
}
