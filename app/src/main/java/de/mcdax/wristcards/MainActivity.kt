package de.mcdax.wristcards

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

class MainActivity : ComponentActivity() {
    // Wear OS treats a right swipe from anywhere as back; the starting point decides whether it is rating.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            lastTouchDownX = ev.x
            lastTouchDownY = ev.y
            lastTouchDownTime = ev.downTime
        }
        // Very fast left swipes also arrive without intermediate points; the card would not see them as a swipe.
        // The study view handles each touch only once, so passing the event on to the card does no harm.
        val left = onLeftSwipe
        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP && ev.downTime == lastTouchDownTime && left != null &&
            Gestures.isLeftSwipe(ev.x - lastTouchDownX, ev.y - lastTouchDownY, 80 * resources.displayMetrics.density)
        ) left(ev.x - lastTouchDownX)
        // Here instead of in the card: very fast swipes arrive without intermediate points and would look like a tap there.
        // If the system intercepts the gesture, no ACTION_UP arrives; then the PredictiveBackHandler reports it.
        val handler = onRightSwipe
        val dx = ev.x - lastTouchDownX
        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP && ev.downTime == lastTouchDownTime && handler != null &&
            Gestures.isRightSwipe(dx, ev.y - lastTouchDownY, 50 * resources.displayMetrics.density)
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
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) android.util.Log.d("AnkiResume", "create restored=${savedInstanceState != null}")
        // Prepare in parallel to the first UI composition, not only after the loading frame.
        val startup = lifecycleScope.async(Dispatchers.IO) { openRepository(applicationContext) }
        // Keep the splash until the first real screen is composed, so no empty frame shows in between.
        var contentReady = false
        splash.setKeepOnScreenCondition { !contentReady }
        // While studying, don't dim mid-card (otherwise Wear OS likes to kill the process).
        // Color variant to try out: adb shell am start ... --ei theme 1
        // Debug build only: the activity is exported, foreign intents must not trigger any of this.
        val debugIntent = intent.takeIf { BuildConfig.DEBUG }
        val variant = (debugIntent?.getIntExtra("theme", 0) ?: 0).coerceIn(themeVariants.indices)
        // For screenshots in the emulator: --es deck <name> opens the deck directly.
        val openDeck = debugIntent?.getStringExtra("deck")
        val previewCard = debugIntent?.getLongExtra("card", 0L)?.takeIf { it != 0L }
        setContent { AnkiTheme(themeVariants[variant]) { App(startup, openDeck, previewCard, restoring = savedInstanceState != null, onReady = { contentReady = true }) } }
    }
    override fun onResume() {
        super.onResume()
        if (BuildConfig.DEBUG) android.util.Log.d("AnkiResume", "resume")
    }
    override fun onStop() {
        super.onStop()
        if (BuildConfig.DEBUG) android.util.Log.d("AnkiResume", "stop")
        // Update the watch face only now, not after every single rating.
        refreshDueComplication(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Background or low memory: images can be reloaded at any time.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) CardImages.trim()
    }
}

/** Result of the start: open the collection and load decks, or the error (e.g. corrupt file). */
internal suspend fun openRepository(context: android.content.Context): Result<AnkiRepository> = runCatching {
    AnkiRepository.get(context).also { it.decks() }
}

/** Logging in the debug build only; in release the text isn't even built. */
internal inline fun debugLog(tag: String, message: () -> String) {
    if (BuildConfig.DEBUG) android.util.Log.d(tag, message())
}

/** Start point of the last touch in pixels. */
internal var lastTouchDownX = 0f
private var lastTouchDownY = 0f
internal var lastTouchDownTime = 0L

/** Set by the study view: right swipe that the app itself gets to see. */
internal var onRightSwipe: (() -> Unit)? = null

/** Set by the study view: left swipe (distance in px) recognized from down and up point alone. */
internal var onLeftSwipe: ((Float) -> Unit)? = null

/** Fraction of the width from which a right swipe is no longer back but rates. */
internal const val BACK_ZONE = 0.20f

internal const val TAG = "AnkiBack"

/** For this long after a rating, right swipes are ignored. */
internal const val RATE_LOCK_MS = 400L
