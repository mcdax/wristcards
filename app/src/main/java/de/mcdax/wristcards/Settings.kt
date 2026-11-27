package de.mcdax.wristcards

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Only watch settings; scheduling/FSRS stays in Anki's deck options.

enum class FontSize { SMALL, NORMAL, LARGE }

/** Explanation/extra field on the back. */
enum class ExtraMode { SHOW, COLLAPSED, HIDE }

enum class AudioMode { AUTO, ON_TAP }

/** BLUETOOTH means: prefer headphones, use the watch speaker without connected headphones. */
enum class AudioOutput { NEVER, BLUETOOTH_ONLY, ALWAYS }

/** What a swipe across the revealed card does. */
enum class SwipeAction { NONE, AGAIN, HARD, GOOD, EASY }

enum class MediaMode { ALL, NONE }

data class AppSettings(
    // Studying
    val fontSize: FontSize = FontSize.NORMAL,
    val extraMode: ExtraMode = ExtraMode.SHOW,
    val audioMode: AudioMode = AudioMode.AUTO,
    val audioOutput: AudioOutput = AudioOutput.BLUETOOTH_ONLY,
    val vibrateOnRate: Boolean = true,
    val vibrateOnDeckDone: Boolean = true,
    // Swipe left = Again, right = Good
    val swipeLeft: SwipeAction = SwipeAction.AGAIN,
    val swipeRight: SwipeAction = SwipeAction.GOOD,
    // Short overlay after every rating, with undo
    val swipeFeedback: Boolean = true,
    val twoButtons: Boolean = false,
    // Words (Again/Hard/Good/Easy) on the buttons; off = only times or symbols
    val showButtonLabels: Boolean = true,
    val showIntervals: Boolean = true,
    // Bar at the edge when the card is longer than the screen
    val showScrollbar: Boolean = true,
    val keepScreenOn: Boolean = true,
    // Sync; both triggers off = manual only (tap/pull down)
    val syncOnOpen: Boolean = true,
    val syncOnDeckExit: Boolean = true,
    // Wi-Fi and phone (Bluetooth) always work; the watch's own LTE only if allowed.
    val cellularSync: Boolean = false,
    val mediaMode: MediaMode = MediaMode.ALL,
    // Deck list
    // Leaving the app only with a second back (button or swipe)
    val confirmExit: Boolean = true,
    val hideEmptyDecks: Boolean = false,
    // "New · Learning · Due" row above the decks
    val showLegend: Boolean = true,
    val pinnedDecks: Set<Long> = emptySet(),
    val hiddenDecks: Set<Long> = emptySet(),
    val collapseSubdecks: Boolean = false,
    // Decks whose collapsed state differs from the default
    val toggledDecks: Set<Long> = emptySet(),
) {
    val swipeToRate: Boolean get() = swipeLeft != SwipeAction.NONE || swipeRight != SwipeAction.NONE

    fun isCollapsed(deckId: Long) = collapseSubdecks != (deckId in toggledDecks)
}

/**
 * Stores [AppSettings] in SharedPreferences and keeps the current state as a StateFlow,
 * so UI and repository can read without a detour via disk.
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
            .putBoolean("showButtonLabels", s.showButtonLabels)
            .putBoolean("showIntervals", s.showIntervals)
            .putBoolean("showScrollbar", s.showScrollbar)
            .putBoolean("showLegend", s.showLegend)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putBoolean("syncOnOpen", s.syncOnOpen)
            .putBoolean("syncOnDeckExit", s.syncOnDeckExit)
            .putBoolean("cellularSync", s.cellularSync)
            .putString("mediaMode", s.mediaMode.name)
            .putBoolean("confirmExit", s.confirmExit)
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
    // Unknown values (e.g. after a rename) silently fall back to the default.
    fun <E : Enum<E>> enum(key: String, default: E, values: Array<E>) =
        p.getString(key, null)?.let { v -> values.firstOrNull { it.name == v } } ?: default
    fun ids(key: String, default: Set<Long>) =
        p.getStringSet(key, null)?.mapNotNullTo(HashSet()) { it.toLongOrNull() } ?: default
    return AppSettings(
        fontSize = enum("fontSize", d.fontSize, FontSize.entries.toTypedArray()),
        extraMode = enum("extraMode", d.extraMode, ExtraMode.entries.toTypedArray()),
        audioMode = enum("audioMode", d.audioMode, AudioMode.entries.toTypedArray()),
        // Migrate older values: audio "OFF" → Never, "BLUETOOTH" → Bluetooth only, "SPEAKER" → always.
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
        // The earlier switch "swipeToRate" corresponded to left = Again, right = Good.
        swipeLeft = if (p.getBoolean("swipeToRate", false) && !p.contains("swipeLeft")) SwipeAction.AGAIN
            else enum("swipeLeft", d.swipeLeft, SwipeAction.entries.toTypedArray()),
        swipeRight = if (p.getBoolean("swipeToRate", false) && !p.contains("swipeRight")) SwipeAction.GOOD
            else enum("swipeRight", d.swipeRight, SwipeAction.entries.toTypedArray()),
        swipeFeedback = p.getBoolean("swipeFeedback", d.swipeFeedback),
        twoButtons = p.getBoolean("twoButtons", d.twoButtons),
        showButtonLabels = p.getBoolean("showButtonLabels", d.showButtonLabels),
        showIntervals = p.getBoolean("showIntervals", d.showIntervals),
        showScrollbar = p.getBoolean("showScrollbar", d.showScrollbar),
        showLegend = p.getBoolean("showLegend", d.showLegend),
        keepScreenOn = p.getBoolean("keepScreenOn", d.keepScreenOn),
        syncOnOpen = p.getBoolean("syncOnOpen", d.syncOnOpen),
        syncOnDeckExit = p.getBoolean("syncOnDeckExit", d.syncOnDeckExit),
        // The earlier switch "wifiOnly" has nothing to migrate: LTE is now off by default.
        cellularSync = p.getBoolean("cellularSync", d.cellularSync),
        mediaMode = enum("mediaMode", d.mediaMode, MediaMode.entries.toTypedArray()),
        confirmExit = p.getBoolean("confirmExit", d.confirmExit),
        hideEmptyDecks = p.getBoolean("hideEmptyDecks", d.hideEmptyDecks),
        pinnedDecks = ids("pinnedDecks", d.pinnedDecks),
        hiddenDecks = ids("hiddenDecks", d.hiddenDecks),
        collapseSubdecks = p.getBoolean("collapseSubdecks", d.collapseSubdecks),
        toggledDecks = ids("toggledDecks", d.toggledDecks),
    )
}

/** Adds [id] or removes it. */
fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id
