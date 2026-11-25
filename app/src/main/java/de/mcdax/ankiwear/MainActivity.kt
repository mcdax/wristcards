package de.mcdax.ankiwear

import android.os.Bundle
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.wear.compose.navigation.currentBackStackEntryAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableLongStateOf
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
    // Wear OS nimmt Rechtswischen von überall als Zurück; der Startpunkt entscheidet, ob es Bewerten ist.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            lastTouchDownX = ev.x
            lastTouchDownY = ev.y
            lastTouchDownTime = ev.downTime
        }
        // Hier statt in der Karte: Sehr schnelles Wischen kommt ohne Zwischenpunkte an und sähe dort wie Tippen aus.
        // Fängt das System die Geste ab, kommt kein ACTION_UP, dann meldet sie der PredictiveBackHandler.
        val handler = onRightSwipe
        val dx = ev.x - lastTouchDownX
        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP && ev.downTime == lastTouchDownTime && handler != null &&
            dx > 50 * resources.displayMetrics.density && kotlin.math.abs(ev.y - lastTouchDownY) < dx / 2
        ) {
            val cancel = android.view.MotionEvent.obtain(ev).apply { action = android.view.MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            handler()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Beim Lernen nicht mitten in der Karte abdunkeln (Wear OS beendet sonst gern den Prozess).
        // Farbvariante zum Ausprobieren: adb shell am start ... --ei theme 1
        val variant = intent.getIntExtra("theme", 0).coerceIn(themeVariants.indices)
        // Für Screenshots im Emulator: --es deck <Name> öffnet das Deck direkt.
        val openDeck = intent.getStringExtra("deck")
        val previewCard = intent.getLongExtra("card", 0L).takeIf { it != 0L }
        setContent { AnkiTheme(themeVariants[variant]) { App(openDeck, previewCard) } }
    }
}

/** Startpunkt der letzten Berührung in Pixeln. */
private var lastTouchDownX = 0f
private var lastTouchDownY = 0f
private var lastTouchDownTime = 0L

/** Von der Lernansicht gesetzt: Rechtswischen, das die App selbst zu sehen bekommt. */
private var onRightSwipe: (() -> Unit)? = null

/** Anteil der Breite, ab dem Rechtswischen nicht mehr Zurück ist, sondern bewertet. */
private const val BACK_ZONE = 0.20f

private const val TAG = "AnkiBack"

private sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data object Done : SyncState
    data class Failed(val message: String) : SyncState
}

@Composable
private fun App(openDeck: String? = null, previewCard: Long? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { AnkiRepository.get(context) }
    val nav = rememberSwipeDismissableNavController()
    // Liegt hier statt in der Deckliste, damit die Scrollposition das Lernen überlebt.
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    var sync by remember { mutableStateOf<SyncState>(SyncState.Idle) }
    var decks by remember { mutableStateOf<List<DeckTreeNode>>(emptyList()) }
    // [settings] Uhr-Einstellungen
    val store = remember { SettingsStore.get(context) }
    val settings by store.flow.collectAsState()
    // Ausgeblendete Decks zählen in der Zifferblatt-Komplikation nicht mit.
    LaunchedEffect(settings.hiddenDecks) { refreshDueComplication(context) }

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
        when {
            previewCard != null -> nav.navigate("preview/$previewCard")
            else -> decks.firstOrNull { it.name == openDeck }?.let { nav.navigate("review/${it.deckId}") }
                ?: run { if (store.current.syncOnOpen) runSync() } // [settings] Auto-Sync beim Öffnen
        }
    }

    // Wischen nach rechts zeigt beim Zurückgehen die Deckliste darunter, statt eines schwarzen Bildschirms.
    // Wisch-Abkürzungen brauchen das Rechtswischen; dann nur per Zurück-Taste aus dem Deck.
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    // Verlassen erschweren: erst zweimal Zurück innerhalb von 2 s schließt die App.
    var exitArmed by remember { mutableStateOf(false) }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            kotlinx.coroutines.delay(2000)
            exitArmed = false
        }
    }
    CompositionLocalProvider(LocalSettings provides settings) {
    SwipeDismissableNavHost(
        navController = nav,
        startDestination = "decks",
        // Auf neuen Wear-OS-Versionen kommt das Zurückwischen als System-Geste vom Rand;
        // Wisch-Bewertung in der Kartenmitte stört das nicht, daher immer an.
        userSwipeEnabled = true,
    ) {
        composable("decks") {
            Box(Modifier.fillMaxSize()) {
            DeckList(
                listState, sync, decks, onSync = ::runSync, onOpen = { nav.navigate("review/${it.deckId}") },
                // [settings] Filter/Anheften/Einklappen und Zugang zu den Einstellungen
                settings = settings, onSettingsChange = store::update, onSettings = { nav.navigate("settings") },
            )

            if (exitArmed) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Text(
                        stringResource(R.string.back_again_to_exit),
                        color = LocalAnkiColors.current.text,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(bottom = 28.dp)
                            .clip(RoundedCornerShape(50))
                            .background(LocalAnkiColors.current.surface)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            }
        }
        // [settings] Einstellungsseiten (SettingsScreen.kt)
        settingsGraph(nav, repo) { decks }
        composable("review/{deckId}") { entry ->
            val deckId = entry.arguments?.getString("deckId")?.toLongOrNull()
            val deck = decks.firstOrNull { it.deckId == deckId } ?: return@composable
            DisposableEffect(deckId) {
                // Bewertungen gleich hochladen, damit sie bei einem späteren Vollabgleich nicht verloren gehen.
                onDispose { if (store.current.syncOnDeckExit) runSync() } // [settings] Auto-Sync nach dem Deck
            }
            ReviewScreen(repo, deck, onBack = { nav.popBackStack() })
        }
        composable("preview/{cardId}") { entry ->
            val cid = entry.arguments?.getString("cardId")?.toLongOrNull() ?: return@composable
            ReviewScreen(repo, deck = null, previewCard = cid, onBack = { nav.popBackStack() })
        }
    }
    // Außerhalb des NavHost: in dessen Startziel kommt Zurück auf der Uhr nicht an.
    // Zurück-Taste und Zurückwischen landen beide hier.
    val activity = context as? android.app.Activity
    androidx.activity.compose.BackHandler(enabled = route == "decks" && settings.confirmExit) {
        if (exitArmed) activity?.finish() else exitArmed = true
    }
    }
}

/** Einstellungen für tief verschachtelte Teile der Lernansicht. */
private val LocalSettings = staticCompositionLocalOf { AppSettings() }

private val AppSettings.textScale: Float
    get() = when (fontSize) {
        FontSize.SMALL -> 0.87f
        FontSize.NORMAL -> 1f
        FontSize.LARGE -> 1.2f
    }

@Composable
private fun DeckList(
    listState: ScalingLazyListState,
    sync: SyncState,
    decks: List<DeckTreeNode>,
    onSync: () -> Unit,
    onOpen: (DeckTreeNode) -> Unit,
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onSettings: () -> Unit,
) {
    val c = LocalAnkiColors.current
    val focus = remember { FocusRequester() }
    // [settings] Deck, für das gerade das Langdruck-Menü offen ist
    var menuFor by remember { mutableStateOf<DeckEntry?>(null) }
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
        // [settings] arrangeDecks (DeckListPrefs.kt) wendet Ausblenden/Anheften/Einklappen an.
        items(arrangeDecks(decks, settings), key = { it.key }) { entry ->
            DeckRow(
                entry, onOpen,
                onLongPress = { menuFor = entry },
                onToggleCollapse = { onSettingsChange { it.copy(toggledDecks = it.toggledDecks.toggle(entry.deck.deckId)) } },
            )
        }
        // [settings] Zugang zu den Einstellungen am Listenende
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
    // [settings] Langdruck-Menü: anheften/lösen, ausblenden
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
            // Unterdecks: eingerückt, etwas dunkler und mit Akzentstrich links, damit die Zugehörigkeit klar ist.
            .padding(start = 10.dp * (entry.level - 1))
            .clip(RoundedCornerShape(15.dp))
            .background(if (sub) c.subSurface else c.surface)
            .combinedClickable(onLongClick = onLongPress) { onOpen(deck) }, // [settings] Langdruck öffnet DeckMenu
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
            modifier = Modifier.padding(start = 6.dp, end = if (entry.hasChildren) 0.dp else 14.dp),
        )
        // [settings] Pfeil zum Auf-/Zuklappen an Oberdecks; rechts, damit der Name die volle Breite hat
        if (entry.hasChildren) {
            Text(
                if (entry.collapsed) "▸" else "▾",
                color = c.accent,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = stringResource(if (entry.collapsed) R.string.deck_expand else R.string.deck_collapse), onClick = onToggleCollapse)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ReviewScreen(repo: AnkiRepository, deck: DeckTreeNode?, previewCard: Long? = null, onBack: () -> Unit = {}) {
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
    val context = LocalContext.current
    val settings = LocalSettings.current
    val audio = remember { CardAudio(context) }
    var zoomed by remember { mutableStateOf<ImageBitmap?>(null) }
    var extraOpen by remember { mutableStateOf(false) }
    // Einblendung nach Bewertung per Wischen: Text und Farbe
    var swipeNote by remember { mutableStateOf<Pair<String, Color>?>(null) }
    val ratedLabels = listOf(
        stringResource(R.string.rated_again), stringResource(R.string.rated_hard),
        stringResource(R.string.rated_good), stringResource(R.string.rated_easy),
    )
    LaunchedEffect(swipeNote) {
        if (swipeNote != null) {
            kotlinx.coroutines.delay(800)
            swipeNote = null
        }
    }
    DisposableEffect(Unit) { onDispose { audio.stop() } }
    // Bildschirm nur beim Lernen wach halten, nicht in der Deckliste.
    DisposableEffect(settings.keepScreenOn) {
        val window = (context as? android.app.Activity)?.window
        if (settings.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(deck?.deckId, previewCard) {
        card = if (previewCard != null) {
            repo.cardById(previewCard)
        } else {
            deck?.let { repo.selectDeck(it.deckId) }
            repo.nextCard()
        }
        loaded = true
    }
    // Neue Karte oder aufgedeckt: oben anfangen, Krone wieder auf den Text lenken.
    LaunchedEffect(revealed, card?.queued?.card?.id) {
        scroll.scrollTo(0)
        focus.requestFocus()
        extraOpen = false
        // Wie Anki: beim Zeigen einer Seite deren Audio automatisch abspielen.
        val side = card?.let { if (revealed) it.answer else it.question } ?: return@LaunchedEffect
        if (settings.audioMode == AudioMode.AUTO) {
            audio.play(side.audioIndexes().mapNotNull { side.audio.getOrNull(it)?.let(repo::mediaFile) }, settings.audioOutput)
        } else {
            audio.stop()
        }
    }

    fun rate(current: ReviewCard, rating: CardAnswer.Rating) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                // Vorschau bewertet nichts.
                if (previewCard != null) return@launch
                repo.answer(current, rating)
                if (settings.vibrateOnRate) vibrate(context, android.os.VibrationEffect.EFFECT_TICK)
                card = repo.nextCard()
                if (card == null && settings.vibrateOnDeckDone) vibrate(context, android.os.VibrationEffect.EFFECT_DOUBLE_CLICK)
                revealed = false
                error = null
            } catch (e: Exception) {
                error = ratingFailed
            } finally {
                busy = false
            }
        }
    }

    fun rateBySwipe(current: ReviewCard, action: SwipeAction) {
        // Index wie bei den Knöpfen: Nochmal, Schwer, Gut, Leicht
        val idx = when (action) {
            SwipeAction.AGAIN -> 0
            SwipeAction.HARD -> 1
            SwipeAction.GOOD -> 2
            SwipeAction.EASY -> 3
            SwipeAction.NONE -> return
        }
        val rating = listOf(CardAnswer.Rating.AGAIN, CardAnswer.Rating.HARD, CardAnswer.Rating.GOOD, CardAnswer.Rating.EASY)[idx]
        if (settings.swipeFeedback && !busy) {
            val color = listOf(c.again, c.hard, c.good, c.easy)[idx]
            swipeNote = "${ratedLabels[idx]}  ${current.intervals.getOrNull(idx).orEmpty()}" to color
        }
        android.util.Log.d(TAG, "result rate $action")
        rate(current, rating)
    }

    val current = card
    // Das System fängt Rechtswischen ab, bevor die Karte es sieht. Verdeckt oder vom linken Rand (BACK_ZONE) = zurück,
    // sonst = bewerten. Die Zurück-Taste liefert keinen Wischfortschritt und führt immer zurück.
    val screenWidth = context.resources.displayMetrics.widthPixels
    val swipeMin = with(LocalDensity.current) { 50.dp.toPx() }
    // Karte und System können dieselbe Geste melden; zweimal zurück würde die Deckliste schließen.
    var left by remember { mutableStateOf(false) }
    fun leave() {
        if (left) return
        left = true
        android.util.Log.d(TAG, "result back")
        onBack()
    }
    var handledGesture by remember { mutableLongStateOf(0L) }
    fun swipedRight() {
        // Je Berührung nur einmal: Nach dem Bewerten wäre die nächste Karte verdeckt und derselbe Wisch ein Zurück.
        if (handledGesture == lastTouchDownTime) return
        handledGesture = lastTouchDownTime
        val shown = card
        val back = !revealed || shown == null || settings.swipeRight == SwipeAction.NONE || lastTouchDownX < screenWidth * BACK_ZONE
        android.util.Log.d(TAG, "right down=$lastTouchDownX revealed=$revealed card=${shown != null} back=$back")
        if (back) leave() else rateBySwipe(shown, settings.swipeRight)
    }
    // Lambda statt ::swipedRight: Funktionsreferenzen gelten als gleich, der Stand würde nie aktualisiert.
    val latestSwipe by rememberUpdatedState({ swipedRight() })
    DisposableEffect(zoomed == null) {
        val handler = { latestSwipe() }
        if (zoomed == null) onRightSwipe = handler
        onDispose { if (onRightSwipe === handler) onRightSwipe = null }
    }
    androidx.activity.compose.PredictiveBackHandler(enabled = zoomed == null) { progress ->
        var swiped = false
        var lastX = 0f
        var maxX = 0f
        try {
            progress.collect {
                swiped = true
                lastX = it.touchX
                maxX = maxOf(maxX, lastX)
            }
            android.util.Log.d(TAG, "invoked swiped=$swiped down=$lastTouchDownX last=$lastX")
            if (swiped) swipedRight() else leave()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            android.util.Log.d(TAG, "cancelled down=$lastTouchDownX last=$lastX max=$maxX")
            // Schnelles Wischen bricht das System manchmal ab. Zurückgezogene Finger bleiben ein Abbruch.
            if (swiped && lastX - lastTouchDownX > swipeMin && maxX - lastX < swipeMin / 4) swipedRight()
            throw e
        }
    }
    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .rotaryScrollable(RotaryScrollableDefaults.behavior(scrollableState = scroll), focus)
                .verticalScroll(scroll)
                .clickable(enabled = current != null && !revealed) { revealed = true }
                // Wisch-Abkürzung (nur aufgedeckt); Rechtswischen kann auch Zurück sein, siehe swipedRight.
                .then(
                    if (current != null) Modifier.pointerInput(current, revealed, settings) {
                        var dx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dx = 0f },
                            onDragEnd = {
                                when {
                                    dx < -80.dp.toPx() && revealed -> rateBySwipe(current, settings.swipeLeft)
                                }
                            },
                        ) { _, amount -> dx += amount }
                    } else Modifier,
                )
                // Unten Platz für die festen Bewertungsknöpfe lassen.
                .padding(start = 30.dp, end = 26.dp, top = 36.dp, bottom = if (revealed) 100.dp else 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                !loaded -> CenterNote(stringResource(R.string.loading))
                current == null -> CenterNote(stringResource(R.string.done, deck?.name.orEmpty()))
                else -> {
                    val side = if (revealed) current.answer else current.question
                    val cloze = if (revealed) c.answerBg to c.answerFg else c.hintBg to c.hintFg
                    val ctx = BlockContext(repo, side, audio, cloze, onZoom = { zoomed = it })
                    Blocks(side.main, ctx, dim = false)
                    side.hints.forEach { HintChip(it) }
                    if (side.extra.isNotEmpty() && settings.extraMode != ExtraMode.HIDE) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.surface))
                        if (settings.extraMode == ExtraMode.SHOW || extraOpen) {
                            Blocks(side.extra, ctx, dim = true)
                        } else {
                            Text(
                                stringResource(R.string.show_more),
                                color = c.textDim,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(c.surface)
                                    .clickable { extraOpen = true }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
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
            GradeBar(current.intervals, enabled = !busy, twoButtons = settings.twoButtons, showIntervals = settings.showIntervals) { rate(current, it) }
        }
        zoomed?.let { ZoomOverlay(it) { zoomed = null } }
        androidx.compose.animation.AnimatedVisibility(
            visible = swipeNote != null,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            // Unten, klein: über dem unteren Rand, wo nach dem Bewerten die Vorderseite noch Platz hat.
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
        ) {
            val (text, color) = swipeNote ?: ("" to c.text)
            Text(
                text,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(c.surface.copy(alpha = 0.95f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/** Was die Bausteine einer Seite zum Darstellen brauchen. */
private class BlockContext(
    val repo: AnkiRepository,
    val side: CardSide,
    val audio: CardAudio,
    val cloze: Pair<Color, Color>,
    val onZoom: (ImageBitmap) -> Unit,
)

private fun CardSide.audioIndexes(): List<Int> =
    (main + extra).filterIsInstance<Block.Audio>().map { it.index }

@Composable
private fun Blocks(blocks: List<Block>, ctx: BlockContext, dim: Boolean) {
    val c = LocalAnkiColors.current
    // Mehrere Audio-Knöpfe hintereinander in einer Zeile sammeln.
    var i = 0
    while (i < blocks.size) {
        val b = blocks[i]
        if (b is Block.Audio && LocalSettings.current.audioOutput == AudioOutput.NEVER) {
            i++
            continue
        }
        if (b is Block.Audio) {
            val run = blocks.drop(i).takeWhile { it is Block.Audio }.map { (it as Block.Audio).index }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                run.forEach { idx -> AudioButton(idx, ctx) }
            }
            i += run.size
            continue
        }
        when (b) {
            is Block.Text -> if (dim) {
                val k = LocalSettings.current.textScale
                Text(b.text.withClozeColors(ctx.cloze.first, ctx.cloze.second), color = c.textDim, fontSize = 14.sp * k, lineHeight = 19.sp * k)
            } else {
                CardBody(b.text.withClozeColors(ctx.cloze.first, ctx.cloze.second))
            }
            is Block.Image, is Block.MaskedImage, is Block.OccludedImage -> ImageBlock(b, ctx)
            is Block.Audio -> Unit
        }
        i++
    }
}

@Composable
private fun AudioButton(index: Int, ctx: BlockContext) {
    val c = LocalAnkiColors.current
    val output = LocalSettings.current.audioOutput
    val file = ctx.side.audio.getOrNull(index)?.let(ctx.repo::mediaFile)
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(c.surface)
            .clickable(enabled = file != null) { ctx.audio.play(listOfNotNull(file), output) },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (file != null) "🔊" else "🔇", fontSize = 15.sp)
    }
}

@Composable
private fun ImageBlock(block: Block, ctx: BlockContext) {
    val c = LocalAnkiColors.current
    var bitmap by remember(block) { mutableStateOf<ImageBitmap?>(null) }
    var missing by remember(block) { mutableStateOf(false) }
    LaunchedEffect(block) {
        val bmp = when (block) {
            is Block.Image -> ctx.repo.mediaFile(block.file)?.let { CardImages.load(it) }
            is Block.MaskedImage -> ctx.repo.mediaFile(block.image)?.let { CardImages.loadMasked(it, ctx.repo.mediaFile(block.mask)) }
            is Block.OccludedImage -> ctx.repo.mediaFile(block.image)?.let { CardImages.loadOccluded(it, block) }
            else -> null
        }
        bitmap = bmp?.asImageBitmap()
        missing = bmp == null
    }
    val bmp = bitmap
    when {
        bmp != null -> androidx.compose.foundation.Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                // Sehr hohe Bilder begrenzen; Antippen öffnet die Zoomansicht.
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { ctx.onZoom(bmp) },
        )
        missing -> Text("🖼 (missing)", color = c.textDim, fontSize = 12.sp)
        else -> Box(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(8.dp)).background(c.surface))
    }
}

/** Vollbild-Zoom: Finger zum Vergrößern/Verschieben, Krone zoomt, Antippen schließt. */
@Composable
private fun ZoomOverlay(bitmap: ImageBitmap, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val focus = remember { FocusRequester() }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset += pan
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent {
                scale = (scale * (1f + it.verticalScrollPixels / 400f)).coerceIn(1f, 6f)
                true
            }
            .focusRequester(focus)
            .focusable()
            .transformable(state)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
        )
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
    val k = LocalSettings.current.textScale
    Text(
        text,
        color = c.text,
        fontSize = 16.sp * k,
        lineHeight = 21.sp * k,
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
        fontSize = 14.sp * LocalSettings.current.textScale,
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
    twoButtons: Boolean,
    showIntervals: Boolean,
    onRate: (CardAnswer.Rating) -> Unit,
) {
    val c = LocalAnkiColors.current
    // Index passt zu den Intervallen des Kerns (Nochmal, Schwer, Gut, Leicht).
    val all = listOf(
        Triple(0, CardAnswer.Rating.AGAIN, c.again),
        Triple(1, CardAnswer.Rating.HARD, c.hard),
        Triple(2, CardAnswer.Rating.GOOD, c.good),
        Triple(3, CardAnswer.Rating.EASY, c.easy),
    )
    val buttons = if (twoButtons) all.filter { it.first == 0 || it.first == 2 } else all
    val symbols = listOf("✕", "–", "✓", "★")
    Row(
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        buttons.forEachIndexed { pos, (i, rating, color) ->
            val outer = !twoButtons && (pos == 0 || pos == buttons.lastIndex)
            Box(
                modifier = Modifier
                    .padding(bottom = if (outer) 10.dp else 0.dp, start = if (twoButtons && pos == 1) 18.dp else 0.dp)
                    .size(if (twoButtons) 44.dp else 36.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (enabled) 0.22f else 0.08f))
                    .clickable(enabled = enabled) { onRate(rating) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (showIntervals) intervals.getOrElse(i) { "" } else symbols[i],
                    color = color,
                    fontSize = if (showIntervals) 11.sp else 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun vibrate(context: android.content.Context, effect: Int) {
    val vibrator = context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator ?: return
    runCatching { vibrator.vibrate(android.os.VibrationEffect.createPredefined(effect)) }
}
