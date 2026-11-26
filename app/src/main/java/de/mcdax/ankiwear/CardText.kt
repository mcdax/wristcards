package de.mcdax.ankiwear

import android.graphics.Typeface
import android.text.Html
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/** Ein Baustein einer Kartenseite; die Uhr stellt jeden nativ dar (kein WebView). */
sealed interface Block {
    data class Text(val text: AnnotatedString) : Block
    data class Image(val file: String) : Block
    /** Image Occlusion Enhanced: Bild plus fertige SVG-Maske darüber. */
    data class MaskedImage(val image: String, val mask: String) : Block
    /** Natives Image Occlusion: Bild plus Formen aus den Cloze-Daten. */
    data class OccludedImage(val image: String, val shapes: List<OcclusionShape>, val revealed: Boolean) : Block
    /** Audio aus der Karte; index verweist auf die AV-Tags dieser Seite. */
    data class Audio(val index: Int) : Block
}

data class OcclusionShape(
    val kind: String, // rect, ellipse, polygon
    val left: Float, val top: Float, val width: Float, val height: Float,
    val points: List<Pair<Float, Float>>,
    val active: Boolean,
    val occludeInactive: Boolean,
)

/** Eine Kartenseite, aufbereitet für die Uhr. */
data class CardSide(
    val main: List<Block>,
    // Teil nach <hr id=extra>: Erklärung, die man nur bei Bedarf liest.
    val extra: List<Block>,
    // Inhalte von {{hint:Feld}}, werden erst auf Antippen gezeigt.
    val hints: List<AnnotatedString>,
    // Dateinamen der Audio-Tags in Reihenfolge ihrer Indizes (TTS-Tags als null).
    val audio: List<String?>,
)

// Private-Use-Zeichen markieren Lücken durch Html.fromHtml hindurch.
private const val CLOZE_START = ''
private const val CLOZE_END = ''

private val STRIP_BLOCKS = Regex("""<(style|script)\b[\s\S]*?</\1>""", RegexOption.IGNORE_CASE)
private val BUTTON = Regex("""<button\b[\s\S]*?</button>""", RegexOption.IGNORE_CASE)
private val PLAY_TAG = Regex("""\[anki:play:[qa]:(\d+)]""")
private val IMG = Regex("""<img\b[^>]*?src\s*=\s*["']?([^"'\s>]+)["']?[^>]*>""", RegexOption.IGNORE_CASE)
private val HR_ANSWER = Regex("""<hr\s+id\s*=\s*["']?answer["']?\s*/?>""", RegexOption.IGNORE_CASE)
private val HR_EXTRA = Regex("""<hr\s+id\s*=\s*["']?extra["']?\s*/?>""", RegexOption.IGNORE_CASE)
private val HINT_FIELD = Regex(
    """<a[^>]*class\s*=\s*["']?hint["']?[^>]*>[\s\S]*?</a>\s*<div[^>]*class\s*=\s*["']?hint["']?[^>]*>([\s\S]*?)</div>""",
    RegexOption.IGNORE_CASE,
)
private val CLOZE_OPEN = Regex("""<span[^>]*class\s*=\s*["']?cloze["'\s>][^>]*>""", RegexOption.IGNORE_CASE)
private val SPAN_TAG = Regex("""<(/?)span\b[^>]*>""", RegexOption.IGNORE_CASE)

// Image Occlusion Enhanced: <div id="io-overlay"><img mask></div> <div id="io-original"><img bild></div>
private val IOE = Regex(
    """<div id="io-overlay">\s*(?:<img[^>]*src="([^"]+)"[^>]*>)?\s*</div>\s*<div id="io-original">\s*<img[^>]*src="([^"]+)"[^>]*>\s*</div>""",
    RegexOption.IGNORE_CASE,
)
// Natives Image Occlusion: Container mit Bild, Formen als Elemente mit data-shape
private val IO_NATIVE_CONTAINER = Regex(
    """<div id="image-occlusion-container">([\s\S]*?)<canvas[^>]*>\s*</canvas>\s*</div>""",
    RegexOption.IGNORE_CASE,
)
private val IO_SHAPE = Regex("""<(?:div|span)\b([^>]*\bdata-shape\s*=[^>]*)>""", RegexOption.IGNORE_CASE)
private val ATTR = Regex("""([\w-]+)\s*=\s*"([^"]*)"""")
private val MARKER = Regex("""(\d+)""")

// Ultimate-Geography verwendet diese semantischen Klassen für alle vier Vorlagen.
// Nur flache divs umformen; unbekannte/verschachtelte Inhalte bleiben unverändert.
private val GEO_DIV = Regex("""<div\b([^>]*)>((?:(?!<div\b)[\s\S])*?)</div>""", RegexOption.IGNORE_CASE)
private val GEO_CLASS = Regex("""\bclass\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
private fun isGeography(html: String): Boolean =
    html.contains("value--top") && GEO_DIV.findAll(html).any {
        "type" in GEO_CLASS.find(it.groupValues[1])?.groupValues?.get(1).orEmpty().split(Regex("\\s+"))
    }

private fun prepareGeography(html: String): String = GEO_DIV.replace(html) { div ->
    val classes = GEO_CLASS.find(div.groupValues[1])?.groupValues?.get(1).orEmpty().split(Regex("\\s+"))
    var content = div.groupValues[2]
    if ("value--front" in classes || "value--back" in classes) {
        val images = IMG.findAll(content).toList()
        val discard = mutableSetOf<Int>()
        images.zipWithNext().forEachIndexed { index, (blur, clear) ->
            // Entspricht der CSS-Regel: auf der Frage nur unscharf, auf der Antwort nur klar.
            if ("-blur" in blur.groupValues[1] && "-blur" !in clear.groupValues[1] &&
                content.substring(blur.range.last + 1, clear.range.first).isBlank()) {
                discard += if ("value--front" in classes) index + 1 else index
            }
        }
        var index = 0
        content = IMG.replace(content) { if (index++ in discard) "" else it.value }
    }
    content = when {
        "type" in classes -> "<small><b>$content</b></small>"
        "info" in classes -> "<small><i>$content</i></small>"
        else -> content
    }
    "<div${div.groupValues[1]}>$content</div>"
}

/** Antwortseite: nur der Teil nach <hr id=answer>, damit die Antwort oben steht. */
fun answerPart(answerHtml: String): String {
    val m = HR_ANSWER.find(answerHtml) ?: return answerHtml
    val answer = answerHtml.substring(m.range.last + 1)
    // Hauptstadt-Antwort zuerst, aber das gefragte Land und seine Hinweise nicht verlieren.
    return if (isGeography(answerHtml)) answer + "<hr>" + answerHtml.substring(0, m.range.first)
        else answer
}

/**
 * [html] ist die gerenderte Seite, in der Audio schon als [anki:play:q:N] steht (extractAvTags),
 * [isAnswer] steuert, ob native IO-Formen aufgedeckt gezeichnet werden.
 */
fun parseSide(html: String, audio: List<String?>, isAnswer: Boolean): CardSide {
    var h = STRIP_BLOCKS.replace(html, "")
    if (isGeography(h)) h = prepareGeography(h)
    h = BUTTON.replace(h, "")
    val hints = HINT_FIELD.findAll(h).map { toAnnotated(it.groupValues[1]) }.toList()
    h = HINT_FIELD.replace(h, "")

    // Sonderblöcke durch Platzhalter ersetzen, damit Text davor/danach erhalten bleibt.
    val special = mutableListOf<Block>()
    fun mark(b: Block): String {
        special += b
        return "${special.size - 1}"
    }
    h = IO_NATIVE_CONTAINER.replace(h) { m ->
        val img = IMG.find(m.groupValues[1])?.groupValues?.get(1) ?: return@replace ""
        mark(Block.OccludedImage(img, nativeShapes(h), isAnswer))
    }
    h = IOE.replace(h) { m ->
        val mask = m.groupValues[1]
        val img = m.groupValues[2]
        mark(if (mask.isBlank()) Block.Image(img) else Block.MaskedImage(img, mask))
    }
    h = IMG.replace(h) { mark(Block.Image(it.groupValues[1])) }
    h = PLAY_TAG.replace(h) { mark(Block.Audio(it.groupValues[1].toInt())) }
    // Die versteckten Cloze-Daten des nativen IO nicht als Text zeigen.
    h = h.replace(Regex("""<div style="display:\s*none">[\s\S]*?</div>""", RegexOption.IGNORE_CASE), "")

    val parts = HR_EXTRA.split(h, limit = 2)
    return CardSide(
        main = toBlocks(parts[0], special),
        extra = parts.getOrNull(1)?.let { toBlocks(it, special) } ?: emptyList(),
        hints = hints,
        audio = audio,
    )
}

private fun toBlocks(html: String, special: List<Block>): List<Block> {
    val out = mutableListOf<Block>()
    var pos = 0
    for (m in MARKER.findAll(html)) {
        addText(out, html.substring(pos, m.range.first))
        val block = special[m.groupValues[1].toInt()]
        // Audio direkt hinter Text: als eigene Zeile, aber mehrere Audios zusammenhängend.
        out += block
        pos = m.range.last + 1
    }
    addText(out, html.substring(pos))
    return out
}

private fun addText(out: MutableList<Block>, html: String) {
    val t = toAnnotated(html)
    if (t.isNotBlank()) out += Block.Text(t)
}

private fun nativeShapes(html: String): List<OcclusionShape> =
    IO_SHAPE.findAll(html).mapNotNull { m ->
        val a = ATTR.findAll(m.groupValues[1]).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        fun f(k: String) = a[k]?.toFloatOrNull() ?: 0f
        val cls = a["class"].orEmpty()
        OcclusionShape(
            kind = a["data-shape"] ?: return@mapNotNull null,
            left = f("data-left"), top = f("data-top"),
            width = f("data-width").takeIf { it > 0 } ?: (f("data-rx") * 2),
            height = f("data-height").takeIf { it > 0 } ?: (f("data-ry") * 2),
            points = a["data-points"].orEmpty().split(' ').mapNotNull { p ->
                val xy = p.split(',')
                if (xy.size == 2) (xy[0].toFloatOrNull() ?: return@mapNotNull null) to (xy[1].toFloatOrNull() ?: return@mapNotNull null) else null
            },
            // Vorderseite: class="cloze", Rückseite: class="cloze-highlight"; alle anderen "cloze-inactive".
            active = cls.split(' ').any { it == "cloze" || it == "cloze-highlight" },
            // Steht nur an der gefragten Form und bedeutet: alle übrigen Formen ebenfalls verdecken.
            occludeInactive = a["data-occludeinactive"] == "1",
        )
    }.toList()

/** Ersetzt <span class="cloze">…</span> (auch verschachtelt) durch Markierungszeichen. */
private fun markClozes(html: String): String {
    val out = StringBuilder()
    var pos = 0
    while (true) {
        val open = CLOZE_OPEN.find(html, pos) ?: break
        out.append(html, pos, open.range.first).append(CLOZE_START)
        var depth = 1
        val cursor = open.range.last + 1
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
        // Code als Monospace; Html.fromHtml kennt nur <tt>.
        .replace(Regex("""<(code|pre)\b[^>]*>""", RegexOption.IGNORE_CASE), "<tt>")
        .replace(Regex("""</(code|pre)>""", RegexOption.IGNORE_CASE), "</tt>")
        .replace(Regex("""<hr\b[^>]*>""", RegexOption.IGNORE_CASE), "<br>")
    val spanned = Html.fromHtml(prepared, Html.FROM_HTML_MODE_COMPACT)

    // Markierungen entfernen, Positionen umrechnen, Lückenbereiche merken.
    val raw = spanned.toString()
    val bullets = inlineBullets(raw)
    val map = IntArray(raw.length + 1)
    val text = StringBuilder()
    val clozes = mutableListOf<IntRange>()
    var clozeStart = -1
    var skip = 0
    for (i in raw.indices) {
        map[i] = text.length
        if (skip > 0) { skip--; continue }
        when (bullets[i]) {
            Bullet.SEPARATOR -> { text.append("\n• "); skip = INLINE_SEPARATOR.length - 1; continue }
            Bullet.AFTER_COLON -> { text.append("\n• "); continue }
            Bullet.BEFORE -> text.append("• ")
            null -> Unit
        }
        when (raw[i]) {
            CLOZE_START -> clozeStart = text.length
            CLOZE_END -> if (clozeStart >= 0) {
                clozes += clozeStart until text.length
                clozeStart = -1
            }
            '￼' -> Unit // Objektzeichen von Html.fromHtml (z. B. übrig gebliebene Bilder)
            else -> text.append(raw[i])
        }
    }
    map[raw.length] = text.length

    // Nur am Rand trimmen, damit Positionen stabil bleiben.
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
            span is RelativeSizeSpan -> SpanStyle(fontSize = span.sizeChange.em)
            span is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
            span is TypefaceSpan && span.family == "monospace" -> SpanStyle(fontFamily = FontFamily.Monospace)
            // Syntaxfarben aus Code-Blöcken behalten; sie sind für dunklen Hintergrund gedacht.
            // Sehr dunkle Vorlagenfarben (für weißen Hintergrund gedacht) würden auf Schwarz verschwinden.
            span is ForegroundColorSpan && Color(span.foregroundColor).luminance() > 0.2f ->
                SpanStyle(color = Color(span.foregroundColor))
            else -> null
        } ?: continue
        val r = range(map[spanned.getSpanStart(span)], map[spanned.getSpanEnd(span)]) ?: continue
        b.addStyle(style, r.first, r.last + 1)
    }
    for (c in clozes) {
        val r = range(c.first, c.last + 1) ?: continue
        b.addStringAnnotation(CLOZE_TAG, "", r.first, r.last + 1)
    }
    return b.toAnnotatedString().withHangingBullets()
}

/**
 * Aufzählungen mit hängendem Einzug: umbrochene Zeilen beginnen unter dem Text, nicht unter dem Punkt.
 * Absatz-Stile in Compose trennen selbst; ein zusätzliches „\n“ ergäbe Leerzeilen. Deshalb wird jede Zeile
 * ein eigener Absatz und die Zeilenumbrüche entfallen.
 */
private fun AnnotatedString.withHangingBullets(): AnnotatedString {
    if (!text.startsWith("• ") && !text.contains("\n• ")) return this
    val out = AnnotatedString.Builder()
    var start = 0
    while (start <= text.length) {
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val from = out.length
        // Leere Zeile als Leerzeichen, sonst verschwände der leere Absatz.
        if (end > start) out.append(subSequence(start, end)) else out.append(" ")
        val indent = if (text.startsWith("• ", start)) TextIndent(firstLine = 0.sp, restLine = 0.75.em) else TextIndent.None
        out.addStyle(ParagraphStyle(textIndent = indent), from, out.length)
        start = end + 1
    }
    return out.toAnnotatedString()
}

const val CLOZE_TAG = "cloze"

/** Aufzählungen, die in einer Zeile mit „ · “ getrennt sind, zeigt die Uhr untereinander. */
private const val INLINE_SEPARATOR = " · "

private enum class Bullet { SEPARATOR, AFTER_COLON, BEFORE }

/**
 * Wo im Text Aufzählungspunkte entstehen: jedes „ · “ wird Zeilenumbruch + „• “.
 * Der erste Eintrag einer solchen Zeile bekommt ebenfalls „• “, entweder nach einem Doppelpunkt
 * („Nenne …: A · B“ → Einleitung, dann Liste) oder am Zeilenanfang.
 */
private fun inlineBullets(raw: String): Map<Int, Bullet> {
    val out = HashMap<Int, Bullet>()
    var lineStart = 0
    while (lineStart <= raw.length) {
        val lineEnd = raw.indexOf('\n', lineStart).let { if (it < 0) raw.length else it }
        val line = raw.substring(lineStart, lineEnd)
        val first = line.indexOf(INLINE_SEPARATOR)
        if (first >= 0) {
            var at = first
            while (at >= 0) {
                out[lineStart + at] = Bullet.SEPARATOR
                at = line.indexOf(INLINE_SEPARATOR, at + INLINE_SEPARATOR.length)
            }
            val colon = line.lastIndexOf(": ", first)
            if (colon >= 0) out[lineStart + colon + 1] = Bullet.AFTER_COLON
            else line.indexOfFirst { !it.isWhitespace() }.takeIf { it in 0 until first }?.let { out[lineStart + it] = Bullet.BEFORE }
        }
        lineStart = lineEnd + 1
    }
    return out
}

/** Färbt die markierten Lücken ein; Vorder- und Rückseite verschieden. */
fun AnnotatedString.withClozeColors(bg: Color, fg: Color): AnnotatedString {
    val marks = getStringAnnotations(CLOZE_TAG, 0, length)
    if (marks.isEmpty()) return this
    val b = AnnotatedString.Builder(this)
    for (m in marks) b.addStyle(SpanStyle(background = bg, color = fg, fontWeight = FontWeight.Medium), m.start, m.end)
    return b.toAnnotatedString()
}
