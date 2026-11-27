package de.mcdax.ankiwear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

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
        if (BuildConfig.DEBUG) android.util.Log.d("AnkiResume", "create restored=${savedInstanceState != null}")
        // Parallel zur ersten UI-Komposition vorbereiten, nicht erst nach der Loading-Frame.
        val startup = lifecycleScope.async(Dispatchers.IO) { openRepository(applicationContext) }
        // Beim Lernen nicht mitten in der Karte abdunkeln (Wear OS beendet sonst gern den Prozess).
        // Farbvariante zum Ausprobieren: adb shell am start ... --ei theme 1
        // Nur im Debug-Build: Die Activity ist exportiert, fremde Intents sollen nichts davon auslösen.
        val debugIntent = intent.takeIf { BuildConfig.DEBUG }
        val variant = (debugIntent?.getIntExtra("theme", 0) ?: 0).coerceIn(themeVariants.indices)
        // Für Screenshots im Emulator: --es deck <Name> öffnet das Deck direkt.
        val openDeck = debugIntent?.getStringExtra("deck")
        val previewCard = debugIntent?.getLongExtra("card", 0L)?.takeIf { it != 0L }
        setContent { AnkiTheme(themeVariants[variant]) { App(startup, openDeck, previewCard, restoring = savedInstanceState != null) } }
    }
    override fun onResume() {
        super.onResume()
        if (BuildConfig.DEBUG) android.util.Log.d("AnkiResume", "resume")
    }
    override fun onStop() {
        super.onStop()
        if (BuildConfig.DEBUG) android.util.Log.d("AnkiResume", "stop")
        // Zifferblatt erst jetzt aktualisieren, nicht nach jeder einzelnen Bewertung.
        refreshDueComplication(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Hintergrund oder knapper Speicher: Bilder lassen sich jederzeit neu laden.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) CardImages.trim()
    }
}

/** Ergebnis des Starts: Sammlung öffnen und Decks laden, oder der Fehler dabei (z. B. beschädigte Datei). */
internal suspend fun openRepository(context: android.content.Context): Result<AnkiRepository> = runCatching {
    AnkiRepository.get(context).also { it.decks() }
}

/** Protokoll nur im Debug-Build; der Text wird im Release gar nicht erst gebaut. */
internal inline fun debugLog(tag: String, message: () -> String) {
    if (BuildConfig.DEBUG) android.util.Log.d(tag, message())
}

/** Startpunkt der letzten Berührung in Pixeln. */
internal var lastTouchDownX = 0f
private var lastTouchDownY = 0f
internal var lastTouchDownTime = 0L

/** Von der Lernansicht gesetzt: Rechtswischen, das die App selbst zu sehen bekommt. */
internal var onRightSwipe: (() -> Unit)? = null

/** Anteil der Breite, ab dem Rechtswischen nicht mehr Zurück ist, sondern bewertet. */
internal const val BACK_ZONE = 0.20f

internal const val TAG = "AnkiBack"

/** So lange nach einer Bewertung wird Rechtswischen ignoriert. */
internal const val RATE_LOCK_MS = 400L
