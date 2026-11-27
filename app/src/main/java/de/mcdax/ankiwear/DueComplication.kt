package de.mcdax.ankiwear

import android.app.PendingIntent
import android.content.ComponentName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** Offene Karten der sichtbaren Decks (ausgeblendete Decks zählen nicht mit). */
data class DueCounts(val new: Int, val learn: Int, val review: Int) {
    val total get() = new + learn + review
}

/**
 * Zahlen fürs Zifferblatt. Oberdecks enthalten ihre Unterdecks schon (mit Ankis Limits); ausgeblendete
 * Unterdecks sichtbarer Oberdecks werden abgezogen. Wegen der Limits des Oberdecks ist das eine Näherung,
 * deshalb nie unter 0.
 */
fun visibleDueCounts(decks: List<anki.decks.DeckTreeNode>, hidden: Set<Long>): DueCounts {
    var new = 0; var learn = 0; var review = 0
    var top: anki.decks.DeckTreeNode? = null
    var topHidden = false
    var hiddenBelow = Int.MAX_VALUE
    var sub = IntArray(3)
    fun flush() {
        val t = top ?: return
        if (!topHidden) {
            new += (t.newCount - sub[0]).coerceAtLeast(0)
            learn += (t.learnCount - sub[1]).coerceAtLeast(0)
            review += (t.reviewCount - sub[2]).coerceAtLeast(0)
        }
    }
    for (d in decks) {
        if (d.level == 1) {
            flush()
            top = d; topHidden = d.deckId in hidden; sub = IntArray(3); hiddenBelow = Int.MAX_VALUE
            continue
        }
        if (topHidden) continue
        // Unter einem ausgeblendeten Unterdeck ist schon alles in dessen Zahlen enthalten.
        if (d.level > hiddenBelow) continue
        hiddenBelow = Int.MAX_VALUE
        if (d.deckId in hidden) {
            sub[0] += d.newCount; sub[1] += d.learnCount; sub[2] += d.reviewCount
            hiddenBelow = d.level
        }
    }
    flush()
    return DueCounts(new, learn, review)
}

/**
 * Läuft die App ohnehin, rechnet der Kern frisch. Sonst gelten die zuletzt gespeicherten Zahlen,
 * solange sie vom selben Tag und höchstens [CACHE_MAX_AGE_MS] alt sind: Für drei Zahlen nicht jedes Mal
 * die native Bibliothek laden und die Sammlung öffnen.
 */
suspend fun dueCounts(context: Context): DueCounts {
    val prefs = context.getSharedPreferences("due_cache", Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    val today = java.time.LocalDate.now().toEpochDay()
    if (!AnkiRepository.isLoaded && prefs.getLong("day", -1) == today && now - prefs.getLong("at", 0) < CACHE_MAX_AGE_MS) {
        return DueCounts(prefs.getInt("new", 0), prefs.getInt("learn", 0), prefs.getInt("review", 0))
    }
    val hidden = SettingsStore.get(context).current.hiddenDecks
    val repo = withContext(Dispatchers.IO) { AnkiRepository.get(context) }
    val counts = visibleDueCounts(repo.decks(), hidden)
    prefs.edit().putLong("day", today).putLong("at", now)
        .putInt("new", counts.new).putInt("learn", counts.learn).putInt("review", counts.review).apply()
    return counts
}

private const val CACHE_MAX_AGE_MS = 2 * 60 * 60 * 1000L

/**
 * Komplikation fürs Zifferblatt: Zahl offener Karten; Antippen öffnet die App.
 * Aktualisiert sich nach dem Lernen (App verlassen), nach Syncs (siehe [refreshDueComplication]) und sonst alle 30 min.
 */
class DueComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, DueCounts(5, 1, 12))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val counts = runCatching { dueCounts(this) }.getOrNull() ?: return null
        return build(request.complicationType, counts)
    }

    private fun build(type: ComplicationType, c: DueCounts): ComplicationData? {
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_cards)).build()
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val total = c.total.toString()
        val desc = PlainComplicationText.Builder(resources.getQuantityString(R.plurals.complication_desc, c.total, c.total)).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(total).build(), desc,
            ).setMonochromaticImage(icon).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                PlainComplicationText.Builder(getString(R.string.complication_long, c.new, c.learn, c.review)).build(), desc,
            ).setTitle(PlainComplicationText.Builder(getString(R.string.app_name)).build())
                .setMonochromaticImage(icon).setTapAction(tap).build()
            else -> null
        }
    }
}

/** Nach Bewertungen/Syncs: Zifferblatt um neue Zahlen bitten. */
fun refreshDueComplication(context: Context) {
    ComplicationDataSourceUpdateRequester.create(
        context, ComponentName(context, DueComplicationService::class.java),
    ).requestUpdateAll()
}
