package de.mcdax.wristcards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Text
import anki.decks.DeckTreeNode

@Composable
internal fun DeckList(
    listState: ScalingLazyListState,
    sync: SyncState,
    decks: List<DeckTreeNode>,
    onSync: () -> Unit,
    onOpen: (DeckTreeNode) -> Unit,
    onResolveConflict: (ConflictChoice) -> Unit,
    onDismissFullDownload: () -> Unit,
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onSettings: () -> Unit,
) {
    val c = LocalAnkiColors.current
    val focus = remember { FocusRequester() }
    // Deck for which the long-press menu is currently open
    var menuFor by remember { mutableStateOf<DeckEntry?>(null) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Pulling down at the top of the list triggers a sync.
    val density = LocalDensity.current
    val threshold = with(density) { 56.dp.toPx() }
    var pull by remember { mutableFloatStateOf(0f) }
    val pullConnection = remember(onSync) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) pull += available.y
                else if (consumed.y < 0f) pull = 0f
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull >= threshold) onSync()
                pull = 0f
                return Velocity.Zero
            }
        }
    }

    // Second stage of the conflict prompt: uploading overwrites the server.
    var confirmUpload by remember { mutableStateOf(false) }
    // The warning replaces the question; bring it to the top so it can be read completely.
    LaunchedEffect(confirmUpload, sync) {
        if (sync == SyncState.NeedsConfirmation) listState.animateScrollToItem(0)
    }
    // Only re-sort when decks or settings change, not on every sync status or pull-down.
    val entries = remember(decks, settings) { arrangeDecks(decks, settings) }
    Box(Modifier.fillMaxSize()) {
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .nestedScroll(pullConnection)
            .rotaryScrollable(RotaryScrollableDefaults.behavior(scrollableState = listState), focus),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        autoCentering = null,
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 28.dp, bottom = 40.dp),
    ) {
        item {
            val ready = pull >= threshold
            val (label, color) = when {
                ready -> stringResource(R.string.release_to_sync) to c.accent
                sync == SyncState.Running -> stringResource(R.string.syncing) to c.textDim
                sync == SyncState.Done -> stringResource(R.string.synced) to c.accent
                sync is SyncState.Failed -> stringResource(R.string.sync_failed) to c.again
                sync == SyncState.NeedsConfirmation -> stringResource(R.string.sync_paused) to c.again
                else -> stringResource(R.string.sync) to c.textDim
            }
            Text(
                label,
                color = color,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier
                    // Moves along visibly when pulling down.
                    .padding(top = with(density) { pull.coerceAtMost(threshold * 1.2f).toDp() } / 2)
                    .clip(RoundedCornerShape(50))
                    .clickable(enabled = sync != SyncState.Running, onClick = onSync)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (sync is SyncState.Failed) {
            item {
                Text(sync.message, color = c.textDim, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 3)
            }
        }
        // Conflict: one side has to win, only on explicit request. Uploading needs a second confirmation.
        // Text and each button are separate list items: the round list scales and scrolls them one by one,
        // so on small displays every button can be scrolled fully into view.
        if (sync == SyncState.NeedsConfirmation) {
            if (!confirmUpload) {
                item { Text(stringResource(R.string.full_download_question), color = c.text, fontSize = 12.sp, textAlign = TextAlign.Center) }
                item { PillButton(stringResource(R.string.full_download_confirm), c.again, c.background) { onResolveConflict(ConflictChoice.USE_SERVER) } }
                item { PillButton(stringResource(R.string.full_upload_offer), c.surface, c.text) { confirmUpload = true } }
                item { PillButton(stringResource(R.string.full_download_cancel), c.surface, c.text, onDismissFullDownload) }
            } else {
                item { Text(stringResource(R.string.full_upload_warning), color = c.again, fontSize = 12.sp, textAlign = TextAlign.Center) }
                item {
                    PillButton(stringResource(R.string.full_upload_confirm), c.again, c.background) {
                        confirmUpload = false
                        onResolveConflict(ConflictChoice.USE_WATCH)
                    }
                }
                item { PillButton(stringResource(R.string.full_download_cancel), c.surface, c.text) { confirmUpload = false } }
            }
        }
        // Legend for the three numbers on the right of each deck.
        if (settings.showLegend) item {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.newCount)) { append(stringResource(R.string.legend_new)) }
                    append("  ·  ")
                    withStyle(SpanStyle(color = c.learnCount)) { append(stringResource(R.string.legend_learn)) }
                    append("  ·  ")
                    withStyle(SpanStyle(color = c.reviewCount)) { append(stringResource(R.string.legend_due)) }
                },
                color = c.textDim,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
        // arrangeDecks (DeckListPrefs.kt) applies hiding/pinning/collapsing.
        items(entries, key = { it.key }) { entry ->
            DeckRow(
                entry, onOpen,
                onLongPress = { menuFor = entry },
                onToggleCollapse = { onSettingsChange { it.copy(toggledDecks = it.toggledDecks.toggle(entry.deck.deckId)) } },
            )
        }
        // Access to the settings at the end of the list
        item {
            Text(
                stringResource(R.string.open_settings),
                color = c.textDim,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onSettings)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
    // Long-press menu: pin/unpin, hide
    menuFor?.let { entry ->
        val id = entry.deck.deckId
        DeckMenu(
            entry.deck,
            pinned = id in settings.pinnedDecks,
            onTogglePin = { onSettingsChange { it.copy(pinnedDecks = it.pinnedDecks.toggle(id)) }; menuFor = null },
            onHide = { onSettingsChange { it.copy(hiddenDecks = it.hiddenDecks + id, pinnedDecks = it.pinnedDecks - id) }; menuFor = null },
            onDismiss = { menuFor = null },
        )
    }
    }
}

@Composable
private fun DeckRow(
    entry: DeckEntry,
    onOpen: (DeckTreeNode) -> Unit,
    onLongPress: () -> Unit,
    onToggleCollapse: () -> Unit,
) {
    val c = LocalAnkiColors.current
    val deck = entry.deck
    val empty = deck.newCount + deck.learnCount + deck.reviewCount == 0
    val sub = entry.level > 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Subdecks: indented, slightly darker and with an accent bar on the left so the hierarchy is clear.
            .padding(start = 10.dp * (entry.level - 1))
            .clip(RoundedCornerShape(15.dp))
            .background(if (sub) c.subSurface else c.surface)
            .combinedClickable(onLongClick = onLongPress) { onOpen(deck) }, // long press opens DeckMenu
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sub) {
            Box(
                Modifier
                    .padding(start = 7.dp)
                    .width(3.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.accent.copy(alpha = 0.55f)),
            )
        }
        Text(
            (if (entry.pinned && entry.key.startsWith("pin-")) "★ " else "") + deck.name,
            color = if (empty) c.textDim else c.text,
            fontSize = if (sub) 14.sp else 15.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // Hyphenate long words by syllable instead of "Allgemeinwisse/n".
            style = androidx.compose.ui.text.TextStyle(
                hyphens = androidx.compose.ui.text.style.Hyphens.Auto,
                lineBreak = androidx.compose.ui.text.style.LineBreak.Paragraph,
            ),
            modifier = Modifier.weight(1f).padding(start = if (sub) 8.dp else 14.dp, top = 10.dp, bottom = 10.dp),
        )
        Text(
            buildAnnotatedString {
                fun count(n: Int, color: Color) = withStyle(SpanStyle(color = if (n == 0) c.textDim.copy(alpha = 0.5f) else color)) { append("$n") }
                count(deck.newCount, c.newCount); append("  ")
                count(deck.learnCount, c.learnCount); append("  ")
                count(deck.reviewCount, c.reviewCount)
            },
            fontSize = 13.sp,
            // Enough space on the right so the round edge doesn't cut off the last number.
            modifier = Modifier.padding(start = 6.dp, end = if (entry.hasChildren) 0.dp else 18.dp),
        )
        // Arrow to expand/collapse on parent decks; on the right so the name has the full width
        if (entry.hasChildren) {
            Text(
                if (entry.collapsed) "▸" else "▾",
                color = c.accent,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = stringResource(if (entry.collapsed) R.string.deck_expand else R.string.deck_collapse), onClick = onToggleCollapse)
                    .padding(top = 6.dp),
            )
        }
    }
}
