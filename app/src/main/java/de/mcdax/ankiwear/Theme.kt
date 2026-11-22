package de.mcdax.ankiwear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * Eigene, ruhige Farben statt der dynamischen Systemfarben (die auf der Uhr pink/grün ausfallen).
 * Grundton Graphit, ein Akzent; Lücken und Hinweise bekommen eigene, gedämpfte Hinterlegungen.
 */
data class AnkiColors(
    val name: String,
    val background: Color = Color(0xFF000000),
    val surface: Color = Color(0xFF1E1F22),
    // Unterdecks eine Spur dunkler als ihr Oberdeck
    val subSurface: Color = Color(0xFF151618),
    val text: Color = Color(0xFFE6E6E6),
    val textDim: Color = Color(0xFF9A9DA1),
    val accent: Color,
    // Lücke auf der Vorderseite ("[...]" bzw. "[Hinweis]") und Hinweisfelder
    val hintBg: Color,
    val hintFg: Color,
    // aufgedeckte Lücke auf der Rückseite
    val answerBg: Color,
    val answerFg: Color,
    // Zähler wie in Anki: neu / lernen / fällig
    val newCount: Color = Color(0xFF7FA7D9),
    val learnCount: Color = Color(0xFFD98080),
    val reviewCount: Color = Color(0xFF8DBF8B),
    // Bewertung: Nochmal / Schwer / Gut / Leicht
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
