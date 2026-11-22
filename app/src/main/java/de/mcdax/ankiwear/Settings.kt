package de.mcdax.ankiwear

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Nur Einstellungen der Uhr; Planung/FSRS bleibt in den Deck-Optionen von Anki.

enum class FontSize { SMALL, NORMAL, LARGE }

/** Erklärung/Extra-Feld auf der Rückseite. */
enum class ExtraMode { SHOW, COLLAPSED, HIDE }

enum class AudioMode { AUTO, ON_TAP }

/** BLUETOOTH heißt: Kopfhörer bevorzugen, ohne verbundene Kopfhörer den Uhr-Lautsprecher nehmen. */
enum class AudioOutput { NEVER, BLUETOOTH_ONLY, ALWAYS }

/** Was ein Wisch über die aufgedeckte Karte bewirkt. */
enum class SwipeAction { NONE, AGAIN, HARD, GOOD, EASY }

enum class MediaMode { ALL, NONE }

data class AppSettings(
    // Lernen
    val fontSize: FontSize = FontSize.NORMAL,
    val extraMode: ExtraMode = ExtraMode.SHOW,
    val audioMode: AudioMode = AudioMode.AUTO,
    val audioOutput: AudioOutput = AudioOutput.BLUETOOTH_ONLY,
    val vibrateOnRate: Boolean = true,
    val vibrateOnDeckDone: Boolean = true,
    // Wischen links = Nochmal, rechts = Gut
    val swipeLeft: SwipeAction = SwipeAction.NONE,
    val swipeRight: SwipeAction = SwipeAction.NONE,
    // Kurze Einblendung, welche Bewertung per Wischen vergeben wurde
    val swipeFeedback: Boolean = true,
    val twoButtons: Boolean = false,
    val showIntervals: Boolean = true,
    val keepScreenOn: Boolean = true,
    // Sync; beide Auslöser aus = nur manuell (Antippen/Herunterziehen)
    val syncOnOpen: Boolean = true,
    val syncOnDeckExit: Boolean = true,
    // Aus: erst WLAN versuchen, sonst über Handy/LTE (bisheriges Verhalten)
    val wifiOnly: Boolean = false,
    val mediaMode: MediaMode = MediaMode.ALL,
    // Deckliste
    val hideEmptyDecks: Boolean = false,
    val pinnedDecks: Set<Long> = emptySet(),
    val hiddenDecks: Set<Long> = emptySet(),
    val collapseSubdecks: Boolean = false,
    // Decks, deren Klappzustand vom Standard abweicht
    val toggledDecks: Set<Long> = emptySet(),
) {
    val swipeToRate: Boolean get() = swipeLeft != SwipeAction.NONE || swipeRight != SwipeAction.NONE

    fun isCollapsed(deckId: Long) = collapseSubdecks != (deckId in toggledDecks)
}

/**
 * Speichert [AppSettings] in SharedPreferences und hält den Stand als StateFlow vor,
 * damit Oberfläche und Repository ohne Umweg über die Platte lesen können.
 */
class SettingsStore private constructor(context: Context) {
    companion object {
        @Volatile private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read(prefs))

    val flow: StateFlow<AppSettings> = state.asStateFlow()
    val current: AppSettings get() = state.value

    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(state.value)
        if (next == state.value) return
        write(next)
        state.value = next
    }

    private fun write(s: AppSettings) {
        fun Set<Long>.str() = mapTo(HashSet()) { it.toString() }
        prefs.edit()
            .putString("fontSize", s.fontSize.name)
            .putString("extraMode", s.extraMode.name)
            .putString("audioMode", s.audioMode.name)
            .putString("audioOutput", s.audioOutput.name)
            .putBoolean("vibrateOnRate", s.vibrateOnRate)
            .putBoolean("vibrateOnDeckDone", s.vibrateOnDeckDone)
            .putString("swipeLeft", s.swipeLeft.name)
            .putString("swipeRight", s.swipeRight.name)
            .putBoolean("swipeFeedback", s.swipeFeedback)
            .putBoolean("twoButtons", s.twoButtons)
            .putBoolean("showIntervals", s.showIntervals)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putBoolean("syncOnOpen", s.syncOnOpen)
            .putBoolean("syncOnDeckExit", s.syncOnDeckExit)
            .putBoolean("wifiOnly", s.wifiOnly)
            .putString("mediaMode", s.mediaMode.name)
            .putBoolean("hideEmptyDecks", s.hideEmptyDecks)
            .putStringSet("pinnedDecks", s.pinnedDecks.str())
            .putStringSet("hiddenDecks", s.hiddenDecks.str())
            .putBoolean("collapseSubdecks", s.collapseSubdecks)
            .putStringSet("toggledDecks", s.toggledDecks.str())
            .apply()
    }
}

private fun read(p: SharedPreferences): AppSettings {
    val d = AppSettings()
    // Unbekannte Werte (z. B. nach Umbenennung) fallen still auf den Standard zurück.
    fun <E : Enum<E>> enum(key: String, default: E, values: Array<E>) =
        p.getString(key, null)?.let { v -> values.firstOrNull { it.name == v } } ?: default
    fun ids(key: String, default: Set<Long>) =
        p.getStringSet(key, null)?.mapNotNullTo(HashSet()) { it.toLongOrNull() } ?: default
    return AppSettings(
        fontSize = enum("fontSize", d.fontSize, FontSize.entries.toTypedArray()),
        extraMode = enum("extraMode", d.extraMode, ExtraMode.entries.toTypedArray()),
        audioMode = enum("audioMode", d.audioMode, AudioMode.entries.toTypedArray()),
        // Ältere Werte übernehmen: Audio "OFF" → Never, "BLUETOOTH" → nur Bluetooth, "SPEAKER" → immer.
        audioOutput = when {
            p.getString("audioMode", null) == "OFF" -> AudioOutput.NEVER
            else -> when (p.getString("audioOutput", null)) {
                "BLUETOOTH" -> AudioOutput.BLUETOOTH_ONLY
                "SPEAKER" -> AudioOutput.ALWAYS
                else -> enum("audioOutput", d.audioOutput, AudioOutput.entries.toTypedArray())
            }
        },
        vibrateOnRate = p.getBoolean("vibrateOnRate", d.vibrateOnRate),
        vibrateOnDeckDone = p.getBoolean("vibrateOnDeckDone", d.vibrateOnDeckDone),
        // Früherer Schalter "swipeToRate" entsprach links = Nochmal, rechts = Gut.
        swipeLeft = if (p.getBoolean("swipeToRate", false) && !p.contains("swipeLeft")) SwipeAction.AGAIN
            else enum("swipeLeft", d.swipeLeft, SwipeAction.entries.toTypedArray()),
        swipeRight = if (p.getBoolean("swipeToRate", false) && !p.contains("swipeRight")) SwipeAction.GOOD
            else enum("swipeRight", d.swipeRight, SwipeAction.entries.toTypedArray()),
        swipeFeedback = p.getBoolean("swipeFeedback", d.swipeFeedback),
        twoButtons = p.getBoolean("twoButtons", d.twoButtons),
        showIntervals = p.getBoolean("showIntervals", d.showIntervals),
        keepScreenOn = p.getBoolean("keepScreenOn", d.keepScreenOn),
        syncOnOpen = p.getBoolean("syncOnOpen", d.syncOnOpen),
        syncOnDeckExit = p.getBoolean("syncOnDeckExit", d.syncOnDeckExit),
        wifiOnly = p.getBoolean("wifiOnly", d.wifiOnly),
        mediaMode = enum("mediaMode", d.mediaMode, MediaMode.entries.toTypedArray()),
        hideEmptyDecks = p.getBoolean("hideEmptyDecks", d.hideEmptyDecks),
        pinnedDecks = ids("pinnedDecks", d.pinnedDecks),
        hiddenDecks = ids("hiddenDecks", d.hiddenDecks),
        collapseSubdecks = p.getBoolean("collapseSubdecks", d.collapseSubdecks),
        toggledDecks = ids("toggledDecks", d.toggledDecks),
    )
}

/** Fügt [id] hinzu oder entfernt sie. */
fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id
