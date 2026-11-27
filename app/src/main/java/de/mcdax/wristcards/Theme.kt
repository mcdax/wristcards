package de.mcdax.wristcards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * Own calm colors instead of the dynamic system colors (which come out pink/green on the watch).
 * Graphite base tone, one accent; gaps and hints get their own muted backgrounds.
 */
data class AnkiColors(
    val name: String,
    val background: Color = Color(0xFF000000),
    val surface: Color = Color(0xFF1E1F22),
    // Subdecks a touch darker than their parent deck
    val subSurface: Color = Color(0xFF151618),
    val text: Color = Color(0xFFE6E6E6),
    val textDim: Color = Color(0xFF9A9DA1),
    val accent: Color,
    // Gap on the front ("[...]" or "[hint]") and hint fields
    val hintBg: Color,
    val hintFg: Color,
    // revealed gap on the back
    val answerBg: Color,
    val answerFg: Color,
    // Counters like in Anki: new / learning / due
    val newCount: Color = Color(0xFF7FA7D9),
    val learnCount: Color = Color(0xFFD98080),
    val reviewCount: Color = Color(0xFF8DBF8B),
    // Rating: Again / Hard / Good / Easy
    val again: Color = Color(0xFFE07A7A),
    val hard: Color = Color(0xFFE0A45E),
    val good: Color = Color(0xFF7DBE82),
    val easy: Color = Color(0xFF6FA6E0),
)

val themeVariants = listOf(
    AnkiColors(
        name = "Graphit/Blau",
        accent = Color(0xFF8AB4F8),
        hintBg = Color(0xFF3A3220), hintFg = Color(0xFFF2CD86),
        answerBg = Color(0xFF1F3350), answerFg = Color(0xFFB9D3FF),
    ),
    AnkiColors(
        name = "Graphit/Bernstein",
        accent = Color(0xFFF2B36B),
        hintBg = Color(0xFF2E3138), hintFg = Color(0xFFC9D1DC),
        answerBg = Color(0xFF3D2E1A), answerFg = Color(0xFFFFD39C),
    ),
    AnkiColors(
        name = "Graphit/Petrol",
        accent = Color(0xFF7CC4C0),
        hintBg = Color(0xFF3A3220), hintFg = Color(0xFFF2CD86),
        answerBg = Color(0xFF173836), answerFg = Color(0xFFA6E3DE),
    ),
)

val LocalAnkiColors = staticCompositionLocalOf { themeVariants[0] }

@Composable
fun AnkiTheme(colors: AnkiColors, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme(
            primary = colors.accent,
            onPrimary = Color.Black,
            background = colors.background,
            onBackground = colors.text,
            surfaceContainer = colors.surface,
            onSurface = colors.text,
            onSurfaceVariant = colors.textDim,
        ),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalAnkiColors provides colors) {
            content()
        }
    }
}
