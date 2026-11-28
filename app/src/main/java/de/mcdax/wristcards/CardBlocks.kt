package de.mcdax.wristcards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings for deeply nested parts of the study view. */
// compositionLocalOf: on change only the places that read the settings recompose.
internal val LocalSettings = androidx.compose.runtime.compositionLocalOf { AppSettings() }

private val AppSettings.textScale: Float
    get() = when (fontSize) {
        FontSize.SMALL -> 0.87f
        FontSize.NORMAL -> 1f
        FontSize.LARGE -> 1.2f
    }

/** What the blocks of a page need to render. */
internal class BlockContext(
    val repo: AnkiRepository,
    val side: CardSide,
    val audio: CardAudio,
    val cloze: Pair<Color, Color>,
    /** Answer side: images show a magnifier icon and enlarge on tap. */
    val zoomable: Boolean,
    val onImageTap: (ImageBitmap) -> Unit,
)

internal fun CardSide.audioIndexes(): List<Int> =
    (main + extra).filterIsInstance<Block.Audio>().map { it.index }

@Composable
internal fun Blocks(blocks: List<Block>, ctx: BlockContext, dim: Boolean) {
    val c = LocalAnkiColors.current
    // Collect several consecutive audio buttons into one row.
    var i = 0
    while (i < blocks.size) {
        val b = blocks[i]
        if (b is Block.Audio && LocalSettings.current.audioOutput == AudioOutput.NEVER) {
            i++
            continue
        }
        if (b is Block.Audio) {
            val run = blocks.drop(i).takeWhile { it is Block.Audio }.map { (it as Block.Audio).index }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                run.forEach { idx -> AudioButton(idx, ctx) }
            }
            i += run.size
            continue
        }
        when (b) {
            is Block.Text -> {
                val text = remember(b.text, ctx.cloze) { b.text.withClozeColors(ctx.cloze.first, ctx.cloze.second) }
                if (dim) {
                    val k = LocalSettings.current.textScale
                    Text(text, color = c.textDim, fontSize = 14.sp * k, lineHeight = 19.sp * k)
                } else {
                    CardBody(text)
                }
            }
            is Block.Image, is Block.MaskedImage, is Block.OccludedImage -> ImageBlock(b, ctx)
            is Block.Audio -> Unit
        }
        i++
    }
}

@Composable
private fun AudioButton(index: Int, ctx: BlockContext) {
    val c = LocalAnkiColors.current
    val output = LocalSettings.current.audioOutput
    // Already resolved in the repository (IO), no file access while drawing.
    val file = ctx.side.audioFiles.getOrNull(index)
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(c.surface)
            .clickable(enabled = file != null) { ctx.audio.play(listOfNotNull(file), output) },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (file != null) "🔊" else "🔇", fontSize = 15.sp)
    }
}

@Composable
private fun ImageBlock(block: Block, ctx: BlockContext) {
    val c = LocalAnkiColors.current
    val assets = LocalContext.current.applicationContext.assets
    var bitmap by remember(block) { mutableStateOf<ImageBitmap?>(null) }
    var missing by remember(block) { mutableStateOf(false) }
    LaunchedEffect(block) {
        // Check and decode paths off the main thread.
        val bmp = withContext(Dispatchers.IO) { when (block) {
            is Block.Image -> ctx.repo.mediaFile(block.file)?.let { CardImages.load(it, assets) }
            is Block.MaskedImage -> ctx.repo.mediaFile(block.image)?.let { CardImages.loadMasked(it, ctx.repo.mediaFile(block.mask)) }
            is Block.OccludedImage -> ctx.repo.mediaFile(block.image)?.let { CardImages.loadOccluded(it, block) }
            else -> null
        } }
        bitmap = bmp?.asImageBitmap()
        missing = bmp == null
    }
    val bmp = bitmap
    when {
        bmp != null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(
                bitmap = bmp,
                // Screen readers announce it; the end-to-end test also finds the image by it.
                contentDescription = stringResource(R.string.card_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    // Limit very tall images; on the answer, tapping opens the zoom view.
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { ctx.onImageTap(bmp) },
            )
            if (ctx.zoomable) {
                Text(
                    "🔍",
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(c.background.copy(alpha = 0.7f))
                        .padding(4.dp),
                )
            }
        }
        missing -> Text(stringResource(R.string.image_unavailable), color = c.textDim, fontSize = 12.sp)
        else -> Box(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(8.dp)).background(c.surface))
    }
}

/** Fullscreen zoom: finger or crown zooms, dragging pans, tap, ✕ or back closes. */
@Composable
internal fun ZoomOverlay(bitmap: ImageBitmap, onClose: () -> Unit) {
    val c = LocalAnkiColors.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val focus = remember { FocusRequester() }
    // Pan only so far that the image doesn't leave the screen.
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = clamp(offset + pan, scale)
    }
    // Back first closes the zoom, not the card right away.
    androidx.activity.compose.BackHandler(onBack = onClose)
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        kotlinx.coroutines.delay(2500)
        hint = false
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size = it }
            .onRotaryScrollEvent {
                scale = (scale * (1f + it.verticalScrollPixels / 400f)).coerceIn(1f, 6f)
                offset = clamp(offset, scale)
                true
            }
            .focusRequester(focus)
            .focusable()
            .transformable(state)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
        )
        Text(
            "✕",
            color = c.text,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(c.surface.copy(alpha = 0.85f))
                .clickable(onClickLabel = stringResource(R.string.close), onClick = onClose)
                .padding(top = 7.dp),
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = hint,
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = TOAST_BOTTOM),
        ) {
            Toast(stringResource(R.string.zoom_hint))
        }
    }
}

@Composable
private fun CardBody(text: AnnotatedString) {
    val c = LocalAnkiColors.current
    // Short cards (capital, vocabulary) centered; longer texts read better left-aligned.
    val short = text.length < 60 && '\n' !in text && text.paragraphStyles.isEmpty()
    val k = LocalSettings.current.textScale
    Text(
        text,
        color = c.text,
        fontSize = 16.sp * k,
        lineHeight = 21.sp * k,
        textAlign = if (short) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun HintChip(hint: AnnotatedString) {
    val c = LocalAnkiColors.current
    var open by remember(hint) { mutableStateOf(false) }
    Text(
        if (open) hint else AnnotatedString("💡 " + stringResource(R.string.show_hint)),
        color = c.hintFg,
        fontSize = 14.sp * LocalSettings.current.textScale,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(c.hintBg)
            .clickable { open = !open }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
