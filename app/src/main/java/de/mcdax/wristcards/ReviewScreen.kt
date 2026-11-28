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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import anki.decks.DeckTreeNode
import anki.scheduler.CardAnswer
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
            kotlinx.coroutines.delay(settings.ratedNoteSeconds * 1000L)
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
            if (settings.ratedNoteSeconds > 0) {
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
        val decision = Gestures.rightSwipe(
            downX = lastTouchDownX,
            width = screenWidth,
            hasCard = shown != null,
            revealed = session.revealed,
            rateRightEnabled = settings.swipeRight != SwipeAction.NONE,
            sinceRatingMs = lastTouchDownTime - session.ratedAt,
        )
        debugLog(TAG) { "right down=$lastTouchDownX revealed=${session.revealed} card=${shown != null} -> $decision" }
        when (decision) {
            // A quick second swipe still belongs to the last rating, even if it starts at the edge.
            Gestures.Right.LOCKED -> Unit
            Gestures.Right.BACK -> leave()
            Gestures.Right.RATE -> rateBySwipe(shown!!, settings.swipeRight)
            Gestures.Right.HIDE -> {
                debugLog(TAG) { "result hide" }
                session.hide()
            }
            Gestures.Right.REVEAL -> {
                debugLog(TAG) { "result reveal" }
                session.reveal()
            }
        }
    }
    val leftMin = with(LocalDensity.current) { 80.dp.toPx() }
    // Left swipe, from the card (with intermediate points) or the activity (very fast, without); once per touch.
    fun swipedLeft(dx: Float) {
        val shown = session.card ?: return
        if (handledGesture == lastTouchDownTime) return
        when (Gestures.leftSwipe(dx, leftMin, session.revealed, lastTouchDownTime - session.ratedAt)) {
            Gestures.Left.RATE -> {
                handledGesture = lastTouchDownTime
                rateBySwipe(shown, settings.swipeLeft)
            }
            Gestures.Left.REVEAL -> {
                handledGesture = lastTouchDownTime
                debugLog(TAG) { "result reveal" }
                session.reveal()
            }
            Gestures.Left.NONE -> Unit
        }
    }
    // Lambdas instead of function references: those compare equal, so the state would never update.
    val latestSwipe by rememberUpdatedState({ swipedRight() })
    val latestLeftSwipe by rememberUpdatedState({ dx: Float -> swipedLeft(dx) })
    DisposableEffect(zoomed == null) {
        val handler = { latestSwipe() }
        val leftHandler = { dx: Float -> latestLeftSwipe(dx) }
        if (zoomed == null) {
            onRightSwipe = handler
            onLeftSwipe = leftHandler
        }
        onDispose {
            if (onRightSwipe === handler) onRightSwipe = null
            if (onLeftSwipe === leftHandler) onLeftSwipe = null
        }
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
            if (swiped && Gestures.cancelledBackCounts(lastTouchDownX, lastX, maxX, swipeMin)) swipedRight()
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
                                if (!horizontal) when (Gestures.axis(dx, dy, slop)) {
                                    Gestures.Axis.HORIZONTAL -> horizontal = true
                                    Gestures.Axis.VERTICAL -> return@awaitEachGesture
                                    null -> Unit
                                }
                                if (horizontal) change.consume()
                            }
                            if (!horizontal) return@awaitEachGesture
                            swipedLeft(dx)
                        }
                    } else Modifier,
                )
                .padding(start = 30.dp, end = 26.dp)
                // Short question and answer in the middle of the screen, longer ones start at the top and scroll.
                // With the answer shown, the text runs faded behind the rating buttons (so you see that it
                // continues) and can be scrolled until its last line is fully above them.
                .centeredOnScreen(
                    screen = LocalConfiguration.current.screenHeightDp.dp,
                    top = 36.dp,
                    bottom = if (revealed) gradeBarClearance() + 14.dp else 60.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
            // Text behind the buttons stays faintly visible.
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(gradeBarClearance() + 20.dp)
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.25f to c.background.copy(alpha = 0.75f), 1f to c.background.copy(alpha = 0.92f))),
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
            // Low on the screen, below the card text: after rating, the next card is hidden, so there are no
            // buttons there. The single-row pill is narrow enough for the round edge.
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
        ) {
            val note = ratedNote
            if (note != null) {
                // One row: rating word, divider, undo arrow. The word alone would not fit next to
                // "Undo" spelled out at the bottom of the round display, so the arrow carries the action.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .widthIn(max = TOAST_MAX_WIDTH)
                        .height(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(c.surface.copy(alpha = 0.97f)),
                ) {
                    Text(
                        note.text,
                        color = note.color,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(start = 14.dp, end = 8.dp),
                    )
                    Box(Modifier.width(1.dp).height(16.dp).background(c.textDim.copy(alpha = 0.4f)))
                    val undoLabel = stringResource(R.string.undo)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxHeight()
                            .clickable(onClickLabel = undoLabel) { undo(note) }
                            .padding(start = 8.dp, end = 12.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_undo),
                            contentDescription = undoLabel,
                            tint = c.accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Overlay after a rating; cardId for undo. */
private data class RatedNote(val text: String, val color: Color, val cardId: Long)




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
    val screen = LocalConfiguration.current.screenWidthDp
    val gap = if (twoButtons) 12f else 2f
    // Four buttons on a flat arc must fit the round display; two wide ones sit in the middle.
    val raise = gradeBarRaise(screen)
    val width = if (twoButtons) 70f else gradeButtonWidth(screen, buttons.size, gap, maxWidth = 44f, raisedBottom = 14f + raise)
    // Without words and without times a symbol remains, so the button isn't empty.
    val symbols = listOf("✕", "–", "✓", "★")
    val twoLines = showLabels && showIntervals
    Row(
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(gap.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        buttons.forEachIndexed { pos, i ->
            val outer = !twoButtons && (pos == 0 || pos == buttons.lastIndex)
            val color = colors[i]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(bottom = if (outer) raise.dp else 0.dp)
                    .width(width.dp)
                    .height(if (twoLines) 40.dp else 34.dp)
                    .clip(RoundedCornerShape(14.dp))
                    // Opaque, so card text behind it doesn't show through.
                    .background(c.background)
                    .background(color.copy(alpha = if (enabled) 0.22f else 0.08f))
                    .clickable(enabled = enabled) { onRate(i) },
            ) {
                if (showLabels || !showIntervals) {
                    // Shrinks long words (e.g. "Nochmal" on narrow buttons) instead of cutting them off.
                    androidx.compose.foundation.text.BasicText(
                        if (showLabels) labels[i] else symbols[i],
                        style = androidx.compose.ui.text.TextStyle(color = color, fontWeight = FontWeight.Medium, lineHeight = 11.sp),
                        maxLines = 1,
                        softWrap = false,
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                            minFontSize = 7.sp, maxFontSize = if (showLabels) 10.sp else 15.sp, stepSize = 0.5.sp,
                        ),
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
                if (showIntervals) {
                    androidx.compose.foundation.text.BasicText(
                        intervals.getOrElse(i) { "" },
                        style = androidx.compose.ui.text.TextStyle(
                            color = if (twoLines) color.copy(alpha = 0.8f) else color,
                            fontWeight = if (twoLines) FontWeight.Normal else FontWeight.Medium,
                            lineHeight = 10.sp,
                        ),
                        maxLines = 1,
                        softWrap = false,
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                            minFontSize = 7.sp, maxFontSize = if (twoLines) 9.sp else 11.sp, stepSize = 0.5.sp,
                        ),
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * How far the outer rating buttons are raised: a flat arc on small watches (10 dp at 192 dp and below),
 * rising to 18 dp at 227 dp and above, where the extra room makes all four buttons wider.
 */
internal fun gradeBarRaise(screenDp: Int): Float = 10f + 8f * ((screenDp - 192) / 35f).coerceIn(0f, 1f)

/** Height of the rating row from the bottom edge (padding, raised outer buttons, button height, gap). */
@Composable
/**
 * Places the content in the middle of a [screen]-high area. Growing content moves up just enough to keep
 * [bottom] free; once it no longer fits, it starts at [top] and the height covers both margins (scrolling).
 */
private fun Modifier.centeredOnScreen(screen: Dp, top: Dp, bottom: Dp) = layout { measurable, constraints ->
    val content = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val (y, height) = centeredContent(content.height.toFloat(), screen.toPx(), top.toPx(), bottom.toPx())
    layout(content.width, height.roundToInt()) { content.place(0, y.roundToInt()) }
}

/** Top offset and total height for content of height [content], see [centeredOnScreen]. */
internal fun centeredContent(content: Float, screen: Float, top: Float, bottom: Float): Pair<Float, Float> {
    val y = maxOf(top, minOf((screen - content) / 2, screen - bottom - content))
    return y to maxOf(screen, y + content + bottom)
}

private fun gradeBarClearance() = (14f + gradeBarRaise(LocalConfiguration.current.screenWidthDp) + 40f + 2f).dp

/**
 * Width of one rating button so that the row, with its outer buttons raised to [raisedBottom] dp above the
 * bottom edge, stays inside the round display of [screenDp] dp. The rounded corners may touch the edge.
 */
internal fun gradeButtonWidth(screenDp: Int, count: Int, gap: Float, maxWidth: Float, raisedBottom: Float): Float {
    val r = screenDp / 2f
    val dy = r - raisedBottom
    val reach = kotlin.math.sqrt((r * r - dy * dy).coerceAtLeast(0f)) + 6f
    return ((2 * reach - gap * (count - 1)) / count).coerceIn(24f, maxWidth)
}

private fun vibrate(context: android.content.Context, effect: Int) {
    val vibrator = context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator ?: return
    runCatching { vibrator.vibrate(android.os.VibrationEffect.createPredefined(effect)) }
}
