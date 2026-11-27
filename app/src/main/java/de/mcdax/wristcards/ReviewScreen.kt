package de.mcdax.wristcards

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Text
import anki.decks.DeckTreeNode
import anki.scheduler.CardAnswer
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ReviewScreen(repo: AnkiRepository, deck: DeckTreeNode?, previewCard: Long? = null, onBack: () -> Unit = {}) {
    val c = LocalAnkiColors.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = LocalSettings.current
    val hintPrefs = remember { context.getSharedPreferences("ui_hints", android.content.Context.MODE_PRIVATE) }
    val revealedState = rememberSaveable { mutableStateOf(false) }
    val studiedState = rememberSaveable { mutableStateOf(0) }
    val session = remember(deck?.deckId, previewCard) {
        ReviewSession(repo, deck?.deckId, previewCard, scope, hintPrefs, revealedState, studiedState)
    }
    var lastShownCardId by rememberSaveable { mutableStateOf<Long?>(null) }
    var lastShownReps by rememberSaveable { mutableStateOf(-1) }
    var lastShownAnswer by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val audio = remember { CardAudio(context) }
    var zoomed by remember { mutableStateOf<ImageBitmap?>(null) }
    var extraOpen by rememberSaveable { mutableStateOf(false) }
    // Overlay after every rating, with undo, for as long as it is visible.
    var ratedNote by remember { mutableStateOf<RatedNote?>(null) }
    val ratedLabels = listOf(
        stringResource(R.string.rated_again), stringResource(R.string.rated_hard),
        stringResource(R.string.rated_good), stringResource(R.string.rated_easy),
    )
    LaunchedEffect(ratedNote) {
        if (ratedNote != null) {
            kotlinx.coroutines.delay(RATED_NOTE_MS)
            ratedNote = null
        }
    }
    val errorText = when (val e = session.error) {
        ReviewError.RateFailed -> stringResource(R.string.rating_failed)
        ReviewError.UndoFailed -> stringResource(R.string.undo_failed)
        is ReviewError.LoadFailed -> e.message
        null -> null
    }
    DisposableEffect(Unit) { onDispose { audio.release() } }
    DisposableEffect(context) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                // No background audio: screen off or leaving the app ends the sound.
                audio.stop()
                debugLog("AnkiResume") { "review stopped scroll=${scroll.value} revealed=${session.revealed}" }
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    // Keep the screen awake only while studying, not in the deck list.
    DisposableEffect(settings.keepScreenOn) {
        val window = (context as? android.app.Activity)?.window
        if (settings.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(session) {
        val loadStart = android.os.SystemClock.uptimeMillis()
        session.load()
        // After a rebuild: if it is no longer the same card, it starts hidden.
        val loadedCard = session.card?.queued?.card
        if (lastShownCardId != null && (loadedCard?.id != lastShownCardId || loadedCard?.reps != lastShownReps)) {
            session.revealed = false
        }
        debugLog("AnkiResume") { "review ready wait=${android.os.SystemClock.uptimeMillis() - loadStart}ms revealed=${session.revealed}" }
    }
    LaunchedEffect(session.card?.queued?.card?.id) {
        if (session.card != null && previewCard == null) {
            // The visible card takes priority over its possible successors.
            androidx.compose.runtime.withFrameNanos { }
            kotlinx.coroutines.delay(100)
            try { repo.preloadUpcomingCards() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("AnkiPerf", "Optional preloading skipped", e) }
        }
    }
    // New card or revealed: start at the top, point the crown at the text again.
    LaunchedEffect(session.revealed, session.card?.queued?.card?.id) {
        val shown = session.card ?: return@LaunchedEffect
        val revealed = session.revealed
        val sameSide = lastShownCardId == shown.queued.card.id &&
            lastShownReps == shown.queued.card.reps && lastShownAnswer == revealed
        if (!sameSide) {
            scroll.scrollTo(0)
            extraOpen = false
        }
        lastShownCardId = shown.queued.card.id
        lastShownReps = shown.queued.card.reps
        lastShownAnswer = revealed
        focus.requestFocus()
        // Restoring the same side neither resets the scroll position
        // nor restarts its audio. New sides behave as before.
        val side = if (revealed) shown.answer else shown.question
        if (!sameSide && settings.audioMode == AudioMode.AUTO) {
            audio.play(side.audioIndexes().mapNotNull { side.audioFiles.getOrNull(it) }, settings.audioOutput)
        } else {
            audio.stop()
        }
        if (BuildConfig.DEBUG) {
            androidx.compose.runtime.withFrameNanos { }
            android.util.Log.d("AnkiResume", "side ready same=$sameSide scroll=${scroll.value} revealed=$revealed")
        }
    }

    fun rate(current: ReviewCard, idx: Int) = session.rate(
        current, idx,
        onRated = {
            if (settings.swipeFeedback) {
                ratedNote = RatedNote(ratedLabels[idx], listOf(c.again, c.hard, c.good, c.easy)[idx], current.queued.card.id)
            }
            if (settings.vibrateOnRate) vibrate(context, android.os.VibrationEffect.EFFECT_TICK)
        },
        onDeckDone = { if (settings.vibrateOnDeckDone) vibrate(context, android.os.VibrationEffect.EFFECT_DOUBLE_CLICK) },
    )

    fun rateBySwipe(current: ReviewCard, action: SwipeAction) {
        // Index as for the buttons: Again, Hard, Good, Easy
        val idx = when (action) {
            SwipeAction.AGAIN -> 0
            SwipeAction.HARD -> 1
            SwipeAction.GOOD -> 2
            SwipeAction.EASY -> 3
            SwipeAction.NONE -> return
        }
        debugLog(TAG) { "result rate $action" }
        rate(current, idx)
    }

    fun undo(note: RatedNote) {
        ratedNote = null
        session.undo(note.cardId)
    }

    val current = session.card
    val revealed = session.revealed
    // The system intercepts right swipes before the card sees them. From the left edge (BACK_ZONE) = back,
    // otherwise revealed = rate, hidden = nothing. The back button gives no swipe progress and always goes back.
    val screenWidth = context.resources.displayMetrics.widthPixels
    val swipeMin = with(LocalDensity.current) { 50.dp.toPx() }
    // Card and system may report the same gesture; going back twice would close the deck list.
    var left by remember { mutableStateOf(false) }
    fun leave() {
        if (left) return
        left = true
        debugLog(TAG) { "result back" }
        onBack()
    }
    var handledGesture by remember { mutableLongStateOf(0L) }
    fun swipedRight() {
        // Once per touch: after rating, the next card would be hidden and the same swipe a back.
        if (handledGesture == lastTouchDownTime) return
        handledGesture = lastTouchDownTime
        val shown = session.card
        // A quick second swipe still belongs to the last rating, even if it starts at the edge.
        if (lastTouchDownTime - session.ratedAt < RATE_LOCK_MS) {
            debugLog(TAG) { "right down=$lastTouchDownX ignored (lock)" }
            return
        }
        // Hidden: edge = back to the deck list, further inside = reveal.
        // Revealed: from the center the right-swipe rating applies (if set); otherwise the back swipe
        // only covers the answer again and returns to the question.
        val edge = lastTouchDownX < screenWidth * BACK_ZONE
        val rateRight = shown != null && session.revealed && !edge && settings.swipeRight != SwipeAction.NONE
        debugLog(TAG) { "right down=$lastTouchDownX revealed=${session.revealed} card=${shown != null} edge=$edge" }
        when {
            shown == null -> leave()
            rateRight -> rateBySwipe(shown, settings.swipeRight)
            session.revealed -> {
                debugLog(TAG) { "result hide" }
                session.hide()
            }
            edge -> leave()
            else -> {
                debugLog(TAG) { "result reveal" }
                session.reveal()
            }
        }
    }
    // Lambda instead of ::swipedRight: function references compare equal, so the state would never update.
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
            debugLog(TAG) { "invoked swiped=$swiped down=$lastTouchDownX last=$lastX" }
            if (swiped) swipedRight() else leave()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            debugLog(TAG) { "cancelled down=$lastTouchDownX last=$lastX max=$maxX" }
            // The system sometimes cancels fast swipes. Fingers pulled back stay a cancel.
            if (swiped && lastX - lastTouchDownX > swipeMin && maxX - lastX < swipeMin / 4) swipedRight()
            throw e
        }
    }
    Box(Modifier.fillMaxSize().background(c.background)) {
        // Don't clamp an existing scroll position to 0 with an empty loading column.
        if (session.loaded) Column(
            modifier = Modifier
                .fillMaxSize()
                .rotaryScrollable(RotaryScrollableDefaults.behavior(scrollableState = scroll), focus)
                .verticalScroll(scroll)
                .clickable(enabled = current != null && !revealed) { session.reveal() }
                // Swipe shortcut (revealed only); a right swipe can also be back, see swipedRight.
                .then(
                    if (current != null) Modifier.pointerInput(current, revealed, settings) {
                        // Horizontal only once the movement is clearly horizontal (as on the right: |dy| < |dx|/2).
                        // On the round watch the thumb moves in an arc; diagonal scrolling thus stays scrolling.
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var dx = 0f
                            var dy = 0f
                            var horizontal = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                // Scrolling has taken over the gesture.
                                if (!horizontal && change.isConsumed) return@awaitEachGesture
                                val delta = change.positionChange()
                                dx += delta.x
                                dy += delta.y
                                if (!horizontal) {
                                    if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dy) < kotlin.math.abs(dx) / 2) horizontal = true
                                    else if (kotlin.math.abs(dy) > slop) return@awaitEachGesture
                                }
                                if (horizontal) change.consume()
                            }
                            if (!horizontal) return@awaitEachGesture
                            when {
                                dx < -80.dp.toPx() && session.revealed -> rateBySwipe(current, settings.swipeLeft)
                                // Hidden: left swipe reveals. Not directly after a rating,
                                // otherwise a double swipe reveals the new card unread.
                                dx < -80.dp.toPx() && lastTouchDownTime - session.ratedAt >= RATE_LOCK_MS -> {
                                    debugLog(TAG) { "result reveal" }
                                    session.reveal()
                                }
                            }
                        }
                    } else Modifier,
                )
                // At least screen height so short questions can be centered; longer ones scroll from the top.
                .heightIn(min = LocalConfiguration.current.screenHeightDp.dp)
                // Leave room at the bottom for the fixed rating buttons.
                .padding(start = 30.dp, end = 26.dp, top = 36.dp, bottom = if (revealed) 110.dp else 60.dp),
            // Question centered, answer at the top (explanation and rating buttons belong together).
            verticalArrangement = if (revealed) Arrangement.spacedBy(10.dp) else Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            when {
                // Short local card changes without a flashing loading hint.
                !session.loaded || session.busy -> Unit
                current == null -> when (session.error) {
                    is ReviewError.LoadFailed -> ErrorNote(
                        stringResource(R.string.load_failed), errorText,
                        onRetry = session::retryLoad,
                        onBack = { leave() },
                    )
                    null -> DoneNote(deck?.name.orEmpty(), session.studied, onBack = { leave() })
                    else -> Unit
                }
                else -> {
                    val side = if (revealed) current.answer else current.question
                    val cloze = if (revealed) c.answerBg to c.answerFg else c.hintBg to c.hintFg
                    // Question: tapping the image reveals as everywhere. Answer: tapping enlarges.
                    val ctx = remember(repo, side, cloze, revealed) {
                        BlockContext(repo, side, audio, cloze, zoomable = revealed, onImageTap = { if (revealed) zoomed = it else session.reveal() })
                    }
                    Blocks(side.main, ctx, dim = false)
                    side.hints.forEach { HintChip(it) }
                    // How do you reveal? For the first cards, below the question, scrolls with the text.
                    // Not at the same time as the overlay of the last rating, otherwise two hints at once.
                    if (session.showRevealHint && ratedNote == null) {
                        Text(
                            stringResource(R.string.tap_to_reveal),
                            color = c.textDim,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
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
            if (session.error !is ReviewError.LoadFailed) errorText?.let { Text(it, color = c.again, fontSize = 12.sp) }
        }

        // Indicates that the card continues (text under the buttons).
        // Only if something is really out of sight; every card is minimally scrollable because of the minimum height.
        if (settings.showScrollbar && session.loaded && current != null && scroll.maxValue > with(LocalDensity.current) { 60.dp.toPx() }) {
            androidx.wear.compose.material3.ScrollIndicator(scroll, modifier = Modifier.align(Alignment.CenterEnd))
        }
        if (revealed && current != null) {
            // Text fades out behind the buttons but stays recognizable (so you know you can scroll).
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(96.dp)
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to c.background.copy(alpha = 0.9f), 0.6f to c.background)),
            )
            GradeBar(
                current.intervals, enabled = !session.busy, twoButtons = settings.twoButtons,
                showLabels = settings.showButtonLabels, showIntervals = settings.showIntervals,
            ) { rate(current, it) }
        }
        zoomed?.let { ZoomOverlay(it) { zoomed = null } }
        androidx.compose.animation.AnimatedVisibility(
            visible = ratedNote != null,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            // At the bottom: after rating, the next card is hidden, so there are no buttons there.
            // Somewhat higher where the round screen is wide enough.
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = TOAST_BOTTOM),
        ) {
            val note = ratedNote
            if (note != null) {
                // One below the other instead of side by side: at the bottom of the round display it is too narrow for both in one row.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .widthIn(max = TOAST_MAX_WIDTH)
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.surface.copy(alpha = 0.97f))
                        .padding(top = 6.dp, bottom = 2.dp),
                ) {
                    Text(
                        note.text,
                        color = note.color,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Text(
                        stringResource(R.string.undo),
                        color = c.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { undo(note) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** Overlay after a rating; cardId for undo. */
private data class RatedNote(val text: String, val color: Color, val cardId: Long)

/** For this long the overlay with undo stays. */
private const val RATED_NOTE_MS = 3000L


/** End of a deck: what has been done and a clear way back. */
@Composable
private fun DoneNote(deckName: String, studied: Int, onBack: () -> Unit) {
    val c = LocalAnkiColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
    ) {
        Text(if (studied > 0) "✓" else "☕", color = c.good, fontSize = 28.sp)
        Text(
            if (studied > 0) stringResource(R.string.done_title) else stringResource(R.string.nothing_due),
            color = c.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Text(
            if (studied > 0) androidx.compose.ui.res.pluralStringResource(R.plurals.done_count, studied, studied, deckName)
            else deckName,
            color = c.textDim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.back_to_decks),
            color = c.background,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(50))
                .background(c.accent)
                .clickable(onClick = onBack)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/**
 * Rating buttons on the lower arc: word on top, next review small below.
 * The outer ones sit higher, like the edge of the display. onRate gets the index (Again, Hard, Good, Easy).
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.GradeBar(
    intervals: List<String>,
    enabled: Boolean,
    twoButtons: Boolean,
    showLabels: Boolean,
    showIntervals: Boolean,
    onRate: (Int) -> Unit,
) {
    val c = LocalAnkiColors.current
    val colors = listOf(c.again, c.hard, c.good, c.easy)
    val labels = listOf(
        stringResource(R.string.opt_again), stringResource(R.string.opt_hard),
        stringResource(R.string.opt_good), stringResource(R.string.opt_easy),
    )
    val buttons = if (twoButtons) listOf(0, 2) else listOf(0, 1, 2, 3)
    // Without words and without times a symbol remains, so the button isn't empty.
    val symbols = listOf("✕", "–", "✓", "★")
    val twoLines = showLabels && showIntervals
    Row(
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        buttons.forEachIndexed { pos, i ->
            val outer = !twoButtons && (pos == 0 || pos == buttons.lastIndex)
            val color = colors[i]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(bottom = if (outer) 24.dp else 0.dp, start = if (twoButtons && pos == 1) 10.dp else 0.dp)
                    .width(if (twoButtons) 70.dp else 44.dp)
                    .height(if (twoLines) 40.dp else 34.dp)
                    .clip(RoundedCornerShape(14.dp))
                    // Opaque, so card text behind it doesn't show through.
                    .background(c.background)
                    .background(color.copy(alpha = if (enabled) 0.22f else 0.08f))
                    .clickable(enabled = enabled) { onRate(i) },
            ) {
                if (showLabels || !showIntervals) {
                    Text(
                        if (showLabels) labels[i] else symbols[i],
                        color = color,
                        fontSize = if (showLabels) 10.sp else 15.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
                if (showIntervals) {
                    Text(
                        intervals.getOrElse(i) { "" },
                        color = if (twoLines) color.copy(alpha = 0.8f) else color,
                        fontSize = if (twoLines) 9.sp else 11.sp,
                        lineHeight = 10.sp,
                        fontWeight = if (twoLines) FontWeight.Normal else FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

private fun vibrate(context: android.content.Context, effect: Int) {
    val vibrator = context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator ?: return
    runCatching { vibrator.vibrate(android.os.VibrationEffect.createPredefined(effect)) }
}
