package de.mcdax.ankiwear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** Offene Karten der sichtbaren Decks (ausgeblendete Decks zählen nicht mit). */
data class DueCounts(val new: Int, val learn: Int, val review: Int) {
    val total get() = new + learn + review
}

suspend fun dueCounts(context: Context): DueCounts {
    val hidden = SettingsStore.get(context).current.hiddenDecks
    // Oberste Ebene reicht: deren Zahlen enthalten die Unterdecks schon (mit Ankis Limits).
    val top = AnkiRepository.get(context).decks().filter { it.level == 1 && it.deckId !in hidden }
    return DueCounts(top.sumOf { it.newCount }, top.sumOf { it.learnCount }, top.sumOf { it.reviewCount })
}

/**
 * Komplikation fürs Zifferblatt: Zahl offener Karten; Antippen öffnet die App.
 * Aktualisiert sich nach Bewertungen und Syncs (siehe [refreshDueComplication]) und sonst alle 30 min.
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
        val desc = PlainComplicationText.Builder(getString(R.string.complication_desc, c.total)).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(total).build(), desc,
            ).setMonochromaticImage(icon).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                PlainComplicationText.Builder(getString(R.string.complication_long, c.new, c.learn, c.review)).build(), desc,
            ).setTitle(PlainComplicationText.Builder("Anki").build())
                .setMonochromaticImage(icon).setTapAction(tap).build()
            // Ring füllt sich, je weniger offen ist; Obergrenze grob 100 Karten.
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = c.total.coerceAtMost(100).toFloat(), min = 0f, max = maxOf(100f, c.total.toFloat()), contentDescription = desc,
            ).setText(PlainComplicationText.Builder(total).build())
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
