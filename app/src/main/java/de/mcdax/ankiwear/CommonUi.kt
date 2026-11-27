package de.mcdax.ankiwear

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text

/** Fehler mit kurzer Erklärung und „Erneut versuchen“, optional „Zurück“. */
@Composable
internal fun ErrorNote(title: String, detail: String?, onRetry: () -> Unit, onBack: (() -> Unit)? = null) {
    val c = LocalAnkiColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        modifier = Modifier.fillMaxSize().background(c.background).padding(horizontal = 28.dp),
    ) {
        Text("⚠", color = c.again, fontSize = 24.sp)
        Text(title, color = c.text, fontSize = 15.sp, textAlign = TextAlign.Center)
        detail?.let { Text(it, color = c.textDim, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        PillButton(stringResource(R.string.retry), c.accent, c.background, onRetry)
        onBack?.let { PillButton(stringResource(R.string.back_to_decks), c.surface, c.text, it) }
    }
}

@Composable
internal fun PillButton(label: String, background: Color, foreground: Color, onClick: () -> Unit) {
    Text(
        label,
        color = foreground,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}
