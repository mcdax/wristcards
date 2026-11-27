package de.mcdax.wristcards

import anki.decks.DeckTreeNode
import org.junit.Assert.assertEquals
import org.junit.Test

class DeckLogicTest {
    private fun deck(id: Long, level: Int, name: String, new: Int = 0, learn: Int = 0, review: Int = 0) =
        DeckTreeNode.newBuilder().setDeckId(id).setLevel(level).setName(name)
            .setNewCount(new).setLearnCount(learn).setReviewCount(review).build()

    // Pre-order like AnkiRepository.decks(): A (with A1, A2), B
    private val decks = listOf(
        deck(1, 1, "A", 2, 1, 10),
        deck(11, 2, "A1", 1, 0, 4),
        deck(12, 2, "A2", 1, 1, 6),
        deck(2, 1, "B", 0, 0, 0),
    )

    @Test fun `all visible in tree order`() {
        assertEquals(listOf("A", "A1", "A2", "B"), arrangeDecks(decks, AppSettings()).map { it.deck.name })
    }

    @Test fun `hidden parent deck takes its subdecks along`() {
        val s = AppSettings(hiddenDecks = setOf(1L))
        assertEquals(listOf("B"), arrangeDecks(decks, s).map { it.deck.name })
    }

    @Test fun `hide empty decks`() {
        val s = AppSettings(hideEmptyDecks = true)
        assertEquals(listOf("A", "A1", "A2"), arrangeDecks(decks, s).map { it.deck.name })
    }

    @Test fun `collapsed shows only the parent deck`() {
        val s = AppSettings(collapseSubdecks = true)
        val entries = arrangeDecks(decks, s)
        assertEquals(listOf("A", "B"), entries.map { it.deck.name })
        assertEquals(true, entries.first().collapsed)
    }

    @Test fun `pinned deck additionally appears at the top`() {
        val s = AppSettings(pinnedDecks = setOf(12L))
        assertEquals(listOf("A2", "A", "A1", "A2", "B"), arrangeDecks(decks, s).map { it.deck.name })
    }

    @Test fun `watch face counts visible parent decks`() {
        assertEquals(DueCounts(2, 1, 10), visibleDueCounts(decks, emptySet()))
    }

    @Test fun `watch face subtracts hidden subdeck`() {
        assertEquals(DueCounts(1, 1, 6), visibleDueCounts(decks, setOf(11L)))
    }

    @Test fun `watch face without hidden parent deck`() {
        assertEquals(DueCounts(0, 0, 0), visibleDueCounts(decks, setOf(1L)))
    }

    @Test fun `watch face never negative`() {
        val capped = listOf(deck(1, 1, "A", 0, 0, 3), deck(11, 2, "A1", 0, 0, 5))
        assertEquals(DueCounts(0, 0, 0), visibleDueCounts(capped, setOf(11L)))
    }
}
