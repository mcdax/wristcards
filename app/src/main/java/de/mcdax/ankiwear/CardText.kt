package de.mcdax.ankiwear

import android.graphics.Typeface
import android.text.Html
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/** Eine Kartenseite, aufbereitet für die Uhr. */
data class CardSide(
    val main: AnnotatedString,
    // Teil nach <hr id=extra>: Erklärung, die man nur bei Bedarf liest.
    val extra: AnnotatedString?,
    // Inhalte von {{hint:Feld}}, werden erst auf Antippen gezeigt.
    val hints: List<AnnotatedString>,
)

// Private-Use-Zeichen markieren Lücken durch Html.fromHtml hindurch.
private const val CLOZE_START = ''
private const val CLOZE_END = ''

private val STRIP_BLOCKS = Regex("""<(style|script)[\s\S]*?</\1>""", RegexOption.IGNORE_CASE)
private val AV_TAG = Regex("""\[anki:play:[^\]]*]|\[sound:[^\]]*]""")
private val IMG = Regex("""<img[^>]*>""", RegexOption.IGNORE_CASE)
private val HR_ANSWER = Regex("""<hr\s+id\s*=\s*["']?answer["']?\s*/?>""", RegexOption.IGNORE_CASE)
private val HR_EXTRA = Regex("""<hr\s+id\s*=\s*["']?extra["']?\s*/?>""", RegexOption.IGNORE_CASE)
private val HINT_FIELD = Regex(
    """<a[^>]*class\s*=\s*["']?hint["']?[^>]*>[\s\S]*?</a>\s*<div[^>]*class\s*=\s*["']?hint["']?[^>]*>([\s\S]*?)</div>""",
    RegexOption.IGNORE_CASE,
)
private val CLOZE_OPEN = Regex("""<span[^>]*class\s*=\s*["']?cloze["'\s>][^>]*>""", RegexOption.IGNORE_CASE)
private val SPAN_TAG = Regex("""<(/?)span\b[^>]*>""", RegexOption.IGNORE_CASE)

/** Antwortseite: nur der Teil nach <hr id=answer>, damit die Antwort oben steht. */
fun answerPart(answerHtml: String): String {
    val m = HR_ANSWER.find(answerHtml) ?: return answerHtml
    return answerHtml.substring(m.range.last + 1)
}

fun parseSide(html: String): CardSide {
    var h = STRIP_BLOCKS.replace(html, "")
        .replace(AV_TAG, "🔊 ")
        .replace(IMG, "🖼 ")
    val hints = HINT_FIELD.findAll(h).map { toAnnotated(it.groupValues[1]) }.toList()
    h = HINT_FIELD.replace(h, "")
    val parts = HR_EXTRA.split(h, limit = 2)
    return CardSide(
        main = toAnnotated(parts[0]),
        extra = parts.getOrNull(1)?.let(::toAnnotated)?.takeIf { it.isNotBlank() },
        hints = hints,
    )
}

/** Ersetzt <span class="cloze">…</span> (auch verschachtelt) durch Markierungszeichen. */
private fun markClozes(html: String): String {
    val out = StringBuilder()
    var pos = 0
    while (true) {
        val open = CLOZE_OPEN.find(html, pos) ?: break
        out.append(html, pos, open.range.first).append(CLOZE_START)
        var depth = 1
        var cursor = open.range.last + 1
        var end = html.length
        var closeEnd = html.length
        for (tag in SPAN_TAG.findAll(html, cursor)) {
            depth += if (tag.groupValues[1].isEmpty()) 1 else -1
            if (depth == 0) {
                end = tag.range.first
                closeEnd = tag.range.last + 1
                break
            }
        }
        out.append(html, cursor, end).append(CLOZE_END)
        pos = closeEnd
    }
    out.append(html, pos, html.length)
    return out.toString()
}

private fun toAnnotated(html: String): AnnotatedString {
    val prepared = markClozes(html)
        .replace(Regex("""<li[^>]*>""", RegexOption.IGNORE_CASE), "<br>• ")
        .replace(Regex("""</?(ul|ol|li)[^>]*>""", RegexOption.IGNORE_CASE), "")
    val spanned = Html.fromHtml(prepared, Html.FROM_HTML_MODE_COMPACT)

    // Markierungen entfernen, Positionen umrechnen, Lückenbereiche merken.
    val raw = spanned.toString()
    val map = IntArray(raw.length + 1)
    val text = StringBuilder()
    val clozes = mutableListOf<IntRange>()
    var clozeStart = -1
    for (i in raw.indices) {
        map[i] = text.length
        when (raw[i]) {
            CLOZE_START -> clozeStart = text.length
            CLOZE_END -> if (clozeStart >= 0) {
                clozes += clozeStart until text.length
                clozeStart = -1
            }
            else -> text.append(raw[i])
        }
    }
    map[raw.length] = text.length

    // Leerzeilen zusammenziehen, aber Positionen stabil halten: nur am Rand trimmen.
    val lead = text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) text.length else it }
    val trail = text.indexOfLast { !it.isWhitespace() } + 1
    if (lead >= trail) return AnnotatedString("")

    val b = AnnotatedString.Builder(text.substring(lead, trail))
    fun range(s: Int, e: Int): IntRange? {
        val a = (s - lead).coerceAtLeast(0)
        val z = (e - lead).coerceAtMost(trail - lead)
        return if (a < z) a until z else null
    }
    for (span in spanned.getSpans(0, spanned.length, Any::class.java)) {
        val style = when {
            span is StyleSpan && span.style == Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.SemiBold)
            span is StyleSpan && span.style == Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            span is StyleSpan && span.style == Typeface.BOLD_ITALIC ->
                SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic)
            span is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
            else -> null
        } ?: continue
        val r = range(map[spanned.getSpanStart(span)], map[spanned.getSpanEnd(span)]) ?: continue
        b.addStyle(style, r.first, r.last + 1)
    }
    for (c in clozes) {
        val r = range(c.first, c.last + 1) ?: continue
        b.addStringAnnotation(CLOZE_TAG, "", r.first, r.last + 1)
    }
    return b.toAnnotatedString()
}

const val CLOZE_TAG = "cloze"

/** Färbt die markierten Lücken ein; Vorder- und Rückseite verschieden. */
fun AnnotatedString.withClozeColors(bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color): AnnotatedString {
    val marks = getStringAnnotations(CLOZE_TAG, 0, length)
    if (marks.isEmpty()) return this
    val b = AnnotatedString.Builder(this)
    for (m in marks) b.addStyle(SpanStyle(background = bg, color = fg, fontWeight = FontWeight.Medium), m.start, m.end)
    return b.toAnnotatedString()
}

