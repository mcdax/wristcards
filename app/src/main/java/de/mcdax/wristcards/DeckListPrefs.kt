package de.mcdax.wristcards

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Text
import anki.decks.DeckTreeNode

/** One row of the deck list after settings (empty, pinned, hidden, collapsed) have been applied. */
data class DeckEntry(
    val deck: DeckTreeNode,
    // Indentation like DeckTreeNode.level; pinned decks at the top are always at level 1.
    val level: Int,
    val hasChildren: Boolean,
    val collapsed: Boolean,
    val pinned: Boolean,
    val key: String,
)

private fun DeckTreeNode.isEmpty() = newCount + learnCount + reviewCount == 0

/** [decks] in pre-order, as AnkiRepository.decks() returns them. */
fun arrangeDecks(decks: List<DeckTreeNode>, s: AppSettings): List<DeckEntry> {
    // Hidden decks disappear together with their subdecks.
    val visible = mutableListOf<Int>()
    var skipBelow = Int.MAX_VALUE
    decks.forEachIndexed { i, d ->
        if (d.level > skipBelow) return@forEachIndexed
        skipBelow = Int.MAX_VALUE
        if (d.deckId in s.hiddenDecks) skipBelow = d.level else visible += i
    }
    fun hasChildren(i: Int) = decks.getOrNull(i + 1)?.let { it.level > decks[i].level } == true
    // Empty here means: the deck and all subdecks have nothing to do.
    fun subtreeEmpty(i: Int): Boolean {
        val level = decks[i].level
        var j = i
        while (j < decks.size && (j == i || decks[j].level > level)) {
            if (!decks[j].isEmpty()) return false
            j++
        }
        return true
    }

    val pinned = visible
        .filter { decks[it].deckId in s.pinnedDecks }
        .map { DeckEntry(decks[it], 1, false, false, true, "pin-${decks[it].deckId}") }

    val tree = mutableListOf<DeckEntry>()
    var collapsedBelow = Int.MAX_VALUE
    for (i in visible) {
        val d = decks[i]
        if (d.level > collapsedBelow) continue
        collapsedBelow = Int.MAX_VALUE
        if (s.hideEmptyDecks && subtreeEmpty(i)) {
            collapsedBelow = d.level
            continue
        }
        val children = hasChildren(i)
        val collapsed = children && s.isCollapsed(d.deckId)
        if (collapsed) collapsedBelow = d.level
        tree += DeckEntry(d, d.level, children, collapsed, d.deckId in s.pinnedDecks, d.deckId.toString())
    }
    return pinned + tree
}

/** Small menu after a long press on a deck: pin/unpin or hide. */
@Composable
fun DeckMenu(
    deck: DeckTreeNode,
    pinned: Boolean,
    onTogglePin: () -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalAnkiColors.current
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(c.background.copy(alpha = 0.94f))
            // Tapping outside closes the menu.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                deck.name,
                color = c.text,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            val colors = ButtonDefaults.buttonColors(containerColor = c.surface, contentColor = c.text)
            Button(
                onClick = onTogglePin,
                colors = colors,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(if (pinned) R.string.deck_unpin else R.string.deck_pin)) },
            )
            Button(
                onClick = onHide,
                colors = colors,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.deck_hide)) },
            )
        }
    }
}
