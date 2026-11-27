package de.mcdax.ankiwear

import anki.decks.DeckTreeNode
import org.junit.Assert.assertEquals
import org.junit.Test

class DeckLogicTest {
    private fun deck(id: Long, level: Int, name: String, new: Int = 0, learn: Int = 0, review: Int = 0) =
        DeckTreeNode.newBuilder().setDeckId(id).setLevel(level).setName(name)
            .setNewCount(new).setLearnCount(learn).setReviewCount(review).build()

    // Vorordnung wie AnkiRepository.decks(): A (mit A1, A2), B
    private val decks = listOf(
        deck(1, 1, "A", 2, 1, 10),
        deck(11, 2, "A1", 1, 0, 4),
        deck(12, 2, "A2", 1, 1, 6),
        deck(2, 1, "B", 0, 0, 0),
    )

    @Test fun `alles sichtbar in Baumreihenfolge`() {
        assertEquals(listOf("A", "A1", "A2", "B"), arrangeDecks(decks, AppSettings()).map { it.deck.name })
    }

    @Test fun `ausgeblendetes Oberdeck nimmt Unterdecks mit`() {
        val s = AppSettings(hiddenDecks = setOf(1L))
        assertEquals(listOf("B"), arrangeDecks(decks, s).map { it.deck.name })
    }

    @Test fun `leere Decks ausblenden`() {
        val s = AppSettings(hideEmptyDecks = true)
        assertEquals(listOf("A", "A1", "A2"), arrangeDecks(decks, s).map { it.deck.name })
    }

    @Test fun `eingeklappt zeigt nur das Oberdeck`() {
        val s = AppSettings(collapseSubdecks = true)
        val entries = arrangeDecks(decks, s)
        assertEquals(listOf("A", "B"), entries.map { it.deck.name })
        assertEquals(true, entries.first().collapsed)
    }

    @Test fun `angeheftetes Deck steht zusätzlich oben`() {
        val s = AppSettings(pinnedDecks = setOf(12L))
        assertEquals(listOf("A2", "A", "A1", "A2", "B"), arrangeDecks(decks, s).map { it.deck.name })
    }

    @Test fun `Zifferblatt zählt sichtbare Oberdecks`() {
        assertEquals(DueCounts(2, 1, 10), visibleDueCounts(decks, emptySet()))
    }

    @Test fun `Zifferblatt zieht ausgeblendetes Unterdeck ab`() {
        assertEquals(DueCounts(1, 1, 6), visibleDueCounts(decks, setOf(11L)))
    }

    @Test fun `Zifferblatt ohne ausgeblendetes Oberdeck`() {
        assertEquals(DueCounts(0, 0, 0), visibleDueCounts(decks, setOf(1L)))
    }

    @Test fun `Zifferblatt nie negativ`() {
        val capped = listOf(deck(1, 1, "A", 0, 0, 3), deck(11, 2, "A1", 0, 0, 5))
        assertEquals(DueCounts(0, 0, 0), visibleDueCounts(capped, setOf(11L)))
    }
}
