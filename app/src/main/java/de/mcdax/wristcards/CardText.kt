package de.mcdax.wristcards

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

/** A block of a card page; the watch renders each natively (no WebView). */
sealed interface Block {
    data class Text(val text: AnnotatedString) : Block
    data class Image(val file: String) : Block
    /** Image Occlusion Enhanced: image plus finished SVG mask on top. */
    data class MaskedImage(val image: String, val mask: String) : Block
    /** Native Image Occlusion: image plus shapes from the cloze data. */
    data class OccludedImage(val image: String, val shapes: List<OcclusionShape>, val revealed: Boolean) : Block
    /** Audio from the card; index refers to the AV tags of this page. */
    data class Audio(val index: Int) : Block
}

data class OcclusionShape(
    val kind: String, // rect, ellipse, polygon
    val left: Float, val top: Float, val width: Float, val height: Float,
    val points: List<Pair<Float, Float>>,
    val active: Boolean,
    val occludeInactive: Boolean,
)

/** A card page, prepared for the watch. */
data class CardSide(
    val main: List<Block>,
    // Part after <hr id=extra>: explanation you only read if needed.
    val extra: List<Block>,
    // Contents of {{hint:Field}}, only shown on tap.
    val hints: List<AnnotatedString>,
    // File names of the audio tags in order of their indices (TTS tags as null).
    val audio: List<String?>,
    // The same entries as files in the media folder; resolved by the repository on IO, null = missing.
    val audioFiles: List<java.io.File?> = emptyList(),
)

// Private-use characters mark gaps through Html.fromHtml.
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

// Image Occlusion Enhanced: <div id="io-overlay"><img mask></div> <div id="io-original"><img image></div>
private val IOE = Regex(
    """<div id="io-overlay">\s*(?:<img[^>]*src="([^"]+)"[^>]*>)?\s*</div>\s*<div id="io-original">\s*<img[^>]*src="([^"]+)"[^>]*>\s*</div>""",
    RegexOption.IGNORE_CASE,
)
// Native Image Occlusion: container with image, shapes as elements with data-shape
private val IO_NATIVE_CONTAINER = Regex(
    """<div id="image-occlusion-container">([\s\S]*?)<canvas[^>]*>\s*</canvas>\s*</div>""",
    RegexOption.IGNORE_CASE,
)
private val IO_SHAPE = Regex("""<(?:div|span)\b([^>]*\bdata-shape\s*=[^>]*)>""", RegexOption.IGNORE_CASE)
private val ATTR = Regex("""([\w-]+)\s*=\s*"([^"]*)"""")
private val MARKER = Regex("""(\d+)""")
private val HIDDEN_DIV = Regex("""<div style="display:\s*none">[\s\S]*?</div>""", RegexOption.IGNORE_CASE)
private val WHITESPACE = Regex("\\s+")
private val LI_OPEN = Regex("""<li[^>]*>""", RegexOption.IGNORE_CASE)
private val LIST_TAGS = Regex("""</?(ul|ol|li)[^>]*>""", RegexOption.IGNORE_CASE)
private val CODE_OPEN = Regex("""<(code|pre)\b[^>]*>""", RegexOption.IGNORE_CASE)
private val CODE_CLOSE = Regex("""</(code|pre)>""", RegexOption.IGNORE_CASE)
private val HR_ANY = Regex("""<hr\b[^>]*>""", RegexOption.IGNORE_CASE)

// Ultimate Geography uses these semantic classes for all four templates.
// Only reshape flat divs; unknown/nested content stays unchanged.
private val GEO_DIV = Regex("""<div\b([^>]*)>((?:(?!<div\b)[\s\S])*?)</div>""", RegexOption.IGNORE_CASE)
private val GEO_CLASS = Regex("""\bclass\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
private fun isGeography(html: String): Boolean =
    html.contains("value--top") && GEO_DIV.findAll(html).any {
        "type" in GEO_CLASS.find(it.groupValues[1])?.groupValues?.get(1).orEmpty().split(WHITESPACE)
    }

private fun prepareGeography(html: String): String = GEO_DIV.replace(html) { div ->
    val classes = GEO_CLASS.find(div.groupValues[1])?.groupValues?.get(1).orEmpty().split(WHITESPACE)
    var content = div.groupValues[2]
    if ("value--front" in classes || "value--back" in classes) {
        val images = IMG.findAll(content).toList()
        val discard = mutableSetOf<Int>()
        images.zipWithNext().forEachIndexed { index, (blur, clear) ->
            // Matches the CSS rule: on the question only blurred, on the answer only sharp.
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

/** Answer side: only the part after <hr id=answer>, so the answer is at the top. */
fun answerPart(answerHtml: String): String {
    val m = HR_ANSWER.find(answerHtml) ?: return answerHtml
    val answer = answerHtml.substring(m.range.last + 1)
    // Capital answer first, but don't lose the asked country and its hints.
    return if (isGeography(answerHtml)) answer + "<hr>" + answerHtml.substring(0, m.range.first)
        else answer
}

/**
 * [html] is the rendered page, in which audio already appears as [anki:play:q:N] (extractAvTags),
 * [isAnswer] controls whether native IO shapes are drawn uncovered.
 */
fun parseSide(html: String, audio: List<String?>, isAnswer: Boolean): CardSide {
    var h = STRIP_BLOCKS.replace(html, "")
    if (isGeography(h)) h = prepareGeography(h)
    h = BUTTON.replace(h, "")
    val hints = HINT_FIELD.findAll(h).map { toAnnotated(it.groupValues[1]) }.toList()
    h = HINT_FIELD.replace(h, "")

    // Replace special blocks with placeholders so text before/after is preserved.
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
    // Don't show the hidden cloze data of native IO as text.
    h = h.replace(HIDDEN_DIV, "")

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
        // Audio directly after text: its own line, but keep several audios together.
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
            // Front: class="cloze", back: class="cloze-highlight"; all others "cloze-inactive".
            active = cls.split(' ').any { it == "cloze" || it == "cloze-highlight" },
            // Appears only on the asked shape and means: hide all other shapes as well.
            occludeInactive = a["data-occludeinactive"] == "1",
        )
    }.toList()

/** Replaces <span class="cloze">…</span> (also nested) with marker characters. */
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
        .replace(LI_OPEN, "<br>• ")
        .replace(LIST_TAGS, "")
        // Code as monospace; Html.fromHtml only knows <tt>.
        .replace(CODE_OPEN, "<tt>")
        .replace(CODE_CLOSE, "</tt>")
        .replace(HR_ANY, "<br>")
    val spanned = Html.fromHtml(prepared, Html.FROM_HTML_MODE_COMPACT)

    // Remove markers, convert positions, remember gap ranges.
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
            '￼' -> Unit // Object character from Html.fromHtml (e.g. leftover images)
            else -> text.append(raw[i])
        }
    }
    map[raw.length] = text.length

    // Trim only at the edges so positions stay stable.
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
            // Keep syntax colors from code blocks; they are meant for a dark background.
            // Very dark template colors (meant for a white background) would vanish on black.
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
 * Bulleted lists with hanging indent: wrapped lines start under the text, not under the bullet.
 * Paragraph styles in Compose separate on their own; an extra "\n" would yield blank lines. So each line
 * becomes its own paragraph and the line breaks are dropped.
 */
private fun AnnotatedString.withHangingBullets(): AnnotatedString {
    if (!text.startsWith("• ") && !text.contains("\n• ")) return this
    val out = AnnotatedString.Builder()
    var start = 0
    while (start <= text.length) {
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val from = out.length
        // Empty line as a space, otherwise the empty paragraph would vanish.
        if (end > start) out.append(subSequence(start, end)) else out.append(" ")
        val indent = if (text.startsWith("• ", start)) TextIndent(firstLine = 0.sp, restLine = 0.75.em) else TextIndent.None
        out.addStyle(ParagraphStyle(textIndent = indent), from, out.length)
        start = end + 1
    }
    return out.toAnnotatedString()
}

const val CLOZE_TAG = "cloze"

/** Lists separated by " · " on one line are shown one below the other on the watch. */
private const val INLINE_SEPARATOR = " · "

internal enum class Bullet { SEPARATOR, AFTER_COLON, BEFORE }

/**
 * Where bullets appear in the text: each " · " becomes line break + "• ".
 * The first entry of such a line also gets "• ", either after a colon
 * ("Name …: A · B" → intro, then list) or at the start of the line.
 */
internal fun inlineBullets(raw: String): Map<Int, Bullet> {
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

/** Colors the marked gaps; front and back differ. */
fun AnnotatedString.withClozeColors(bg: Color, fg: Color): AnnotatedString {
    val marks = getStringAnnotations(CLOZE_TAG, 0, length)
    if (marks.isEmpty()) return this
    val b = AnnotatedString.Builder(this)
    for (m in marks) b.addStyle(SpanStyle(background = bg, color = fg, fontWeight = FontWeight.Medium), m.start, m.end)
    return b.toAnnotatedString()
}
