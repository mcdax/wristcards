package de.mcdax.ankiwear

import android.os.Bundle
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Text
import anki.decks.DeckTreeNode
import anki.scheduler.CardAnswer
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Beim Lernen nicht mitten in der Karte abdunkeln (Wear OS beendet sonst gern den Prozess).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Farbvariante zum Ausprobieren: adb shell am start ... --ei theme 1
        val variant = intent.getIntExtra("theme", 0).coerceIn(themeVariants.indices)
        // Für Screenshots im Emulator: --es deck <Name> öffnet das Deck direkt.
        val openDeck = intent.getStringExtra("deck")
        setContent { AnkiTheme(themeVariants[variant]) { App(openDeck) } }
    }
}

private sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data object Done : SyncState
    data class Failed(val message: String) : SyncState
}

@Composable
private fun App(openDeck: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { AnkiRepository.get(context) }
    val nav = rememberSwipeDismissableNavController()
    // Liegt hier statt in der Deckliste, damit die Scrollposition das Lernen überlebt.
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    var sync by remember { mutableStateOf<SyncState>(SyncState.Idle) }
    var decks by remember { mutableStateOf<List<DeckTreeNode>>(emptyList()) }

    fun runSync() {
        if (sync == SyncState.Running) return
        sync = SyncState.Running
        scope.launch {
            sync = when (val r = repo.sync()) {
                SyncResult.Ok -> SyncState.Done
                is SyncResult.Failed -> SyncState.Failed(r.message)
            }
            decks = repo.decks()
        }
    }

    LaunchedEffect(Unit) {
        decks = repo.decks()
        decks.firstOrNull { it.name == openDeck }?.let { nav.navigate("review/${it.deckId}") } ?: runSync()
    }

    // Wischen nach rechts zeigt beim Zurückgehen die Deckliste darunter, statt eines schwarzen Bildschirms.
    SwipeDismissableNavHost(navController = nav, startDestination = "decks") {
        composable("decks") {
            DeckList(listState, sync, decks, onSync = ::runSync, onOpen = { nav.navigate("review/${it.deckId}") })
        }
        composable("review/{deckId}") { entry ->
            val deckId = entry.arguments?.getString("deckId")?.toLongOrNull()
            val deck = decks.firstOrNull { it.deckId == deckId } ?: return@composable
            DisposableEffect(deckId) {
                // Bewertungen gleich hochladen, damit sie bei einem späteren Vollabgleich nicht verloren gehen.
                onDispose { runSync() }
            }
            ReviewScreen(repo, deck)
        }
    }
}

@Composable
private fun DeckList(
    listState: ScalingLazyListState,
    sync: SyncState,
    decks: List<DeckTreeNode>,
    onSync: () -> Unit,
    onOpen: (DeckTreeNode) -> Unit,
) {
    val c = LocalAnkiColors.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Ziehen nach unten am Listenanfang löst einen Sync aus.
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
                else -> stringResource(R.string.sync) to c.textDim
            }
            Text(
                label,
                color = color,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier
                    // Zieht beim Herunterziehen sichtbar mit.
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
        items(decks, key = { it.deckId }) { deck -> DeckRow(deck, onOpen) }
    }
}

@Composable
private fun DeckRow(deck: DeckTreeNode, onOpen: (DeckTreeNode) -> Unit) {
    val c = LocalAnkiColors.current
    val empty = deck.newCount + deck.learnCount + deck.reviewCount == 0
    val sub = deck.level > 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Unterdecks: eingerückt, etwas dunkler und mit Akzentstrich links, damit die Zugehörigkeit klar ist.
            .padding(start = 10.dp * (deck.level - 1))
            .clip(RoundedCornerShape(15.dp))
            .background(if (sub) c.subSurface else c.surface)
            .clickable { onOpen(deck) },
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
            deck.name,
            color = if (empty) c.textDim else c.text,
            fontSize = if (sub) 14.sp else 15.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
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
            modifier = Modifier.padding(start = 6.dp, end = 14.dp),
        )
    }
}

@Composable
private fun ReviewScreen(repo: AnkiRepository, deck: DeckTreeNode) {
    val c = LocalAnkiColors.current
    val scope = rememberCoroutineScope()
    val ratingFailed = stringResource(R.string.rating_failed)
    var card by remember { mutableStateOf<ReviewCard?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(deck.deckId) {
        repo.selectDeck(deck.deckId)
        card = repo.nextCard()
        loaded = true
    }
    // Neue Karte oder aufgedeckt: oben anfangen, Krone wieder auf den Text lenken.
    LaunchedEffect(revealed, card?.queued?.card?.id) {
        scroll.scrollTo(0)
        focus.requestFocus()
    }

    fun rate(current: ReviewCard, rating: CardAnswer.Rating) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                repo.answer(current, rating)
                card = repo.nextCard()
                revealed = false
                error = null
            } catch (e: Exception) {
                error = ratingFailed
            } finally {
                busy = false
            }
        }
    }

    val current = card
    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .rotaryScrollable(RotaryScrollableDefaults.behavior(scrollableState = scroll), focus)
                .verticalScroll(scroll)
                .clickable(enabled = current != null && !revealed) { revealed = true }
                // Unten Platz für die festen Bewertungsknöpfe lassen.
                .padding(start = 30.dp, end = 26.dp, top = 36.dp, bottom = if (revealed) 100.dp else 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                !loaded -> CenterNote(stringResource(R.string.loading))
                current == null -> CenterNote(stringResource(R.string.done, deck.name))
                !revealed -> {
                    CardBody(current.question.main.withClozeColors(c.hintBg, c.hintFg))
                    current.question.hints.forEach { HintChip(it) }
                }
                else -> {
                    CardBody(current.answer.main.withClozeColors(c.answerBg, c.answerFg))
                    current.answer.extra?.let { extra ->
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.surface))
                        Text(extra, color = c.textDim, fontSize = 14.sp, lineHeight = 19.sp)
                    }
                }
            }
            error?.let { Text(it, color = c.again, fontSize = 12.sp) }
        }

        if (revealed && current != null) {
            // Text läuft hinter den Knöpfen weich aus.
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(90.dp)
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to c.background)),
            )
            GradeBar(current.intervals, enabled = !busy) { rate(current, it) }
        }
    }
}

@Composable
private fun CenterNote(text: String) {
    val c = LocalAnkiColors.current
    Text(text, color = c.textDim, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 40.dp))
}

@Composable
private fun CardBody(text: AnnotatedString) {
    val c = LocalAnkiColors.current
    // Kurze Karten (Hauptstadt, Vokabel) mittig, längere Texte linksbündig lesen sich besser.
    val short = text.length < 60 && '\n' !in text
    Text(
        text,
        color = c.text,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        textAlign = if (short) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HintChip(hint: AnnotatedString) {
    val c = LocalAnkiColors.current
    var open by remember(hint) { mutableStateOf(false) }
    Text(
        if (open) hint else AnnotatedString("💡 " + stringResource(R.string.show_hint)),
        color = c.hintFg,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(c.hintBg)
            .clickable { open = !open }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Vier runde Knöpfe auf dem unteren Bogen; die äußeren sitzen höher, wie der Rand des Displays. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.GradeBar(
    intervals: List<String>,
    enabled: Boolean,
    onRate: (CardAnswer.Rating) -> Unit,
) {
    val c = LocalAnkiColors.current
    val buttons = listOf(
        CardAnswer.Rating.AGAIN to c.again,
        CardAnswer.Rating.HARD to c.hard,
        CardAnswer.Rating.GOOD to c.good,
        CardAnswer.Rating.EASY to c.easy,
    )
    Row(
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        buttons.forEachIndexed { i, (rating, color) ->
            Box(
                modifier = Modifier
                    .padding(bottom = if (i == 0 || i == 3) 10.dp else 0.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (enabled) 0.22f else 0.08f))
                    .clickable(enabled = enabled) { onRate(rating) },
                contentAlignment = Alignment.Center,
            ) {
                Text(intervals.getOrElse(i) { "" }, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}
