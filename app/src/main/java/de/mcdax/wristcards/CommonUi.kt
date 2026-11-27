package de.mcdax.wristcards

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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

/** Error with a short explanation and "Try again", optionally "Back". */
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

/** There is little room at the bottom of the round display: keep hints high and narrow enough to stay fully visible. */
internal val TOAST_BOTTOM = 36.dp
internal val TOAST_MAX_WIDTH = 150.dp

/** Short hint at the bottom; wraps to a second line if needed instead of being cut off at the edge. */
@Composable
internal fun Toast(text: String, modifier: Modifier = Modifier) {
    val c = LocalAnkiColors.current
    Text(
        text,
        color = c.text,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = TOAST_MAX_WIDTH)
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface.copy(alpha = 0.95f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Shown instead of the deck list while signed out: app icon, one sentence, a large sign-in button. */
@Composable
internal fun WelcomeScreen(onSignIn: () -> Unit) {
    val c = LocalAnkiColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        modifier = Modifier.fillMaxSize().background(c.background).padding(horizontal = 30.dp),
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
        )
        Text(stringResource(R.string.welcome_text), color = c.text, fontSize = 13.sp, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.sign_in),
            color = c.background,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(c.accent)
                .clickable(onClick = onSignIn)
                .padding(vertical = 14.dp),
        )
    }
}
