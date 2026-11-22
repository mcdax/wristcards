package de.mcdax.ankiwear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.LruCache
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lädt Kartenbilder sparsam: Die Uhr läuft mit 32 Bit und wenig RAM, deshalb immer
 * herunterskaliert auf [MAX_PX] und mit kleinem Cache.
 */
object CardImages {
    private const val MAX_PX = 720
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    suspend fun load(file: File): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(file.path) ?: decode(file)?.also { cache.put(file.path, it) }
    }

    /** Bild mit IOE-Maske (SVG in Bildgröße) darüber. */
    suspend fun loadMasked(image: File, mask: File?): Bitmap? = withContext(Dispatchers.IO) {
        val key = image.path + "|" + mask?.path
        cache.get(key)?.let { return@withContext it }
        val base = decode(image) ?: return@withContext null
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        if (mask != null) runCatching {
            val svg = SVG.getFromInputStream(mask.inputStream())
            svg.setDocumentWidth(out.width.toFloat())
            svg.setDocumentHeight(out.height.toFloat())
            svg.renderToCanvas(Canvas(out))
        }
        cache.put(key, out)
        out
    }

    /** Bild mit nativen IO-Formen; Koordinaten sind relativ (0..1) oder Pixel des Originals. */
    suspend fun loadOccluded(image: File, block: Block.OccludedImage): Bitmap? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.path, bounds)
        val base = decode(image) ?: return@withContext null
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        // Pixelangaben beziehen sich auf das Originalbild, wir zeichnen ins verkleinerte.
        val relative = block.shapes.all { it.left <= 1f && it.top <= 1f && it.width <= 1f && it.height <= 1f }
        val sx = if (relative) out.width.toFloat() else out.width.toFloat() / bounds.outWidth.coerceAtLeast(1)
        val sy = if (relative) out.height.toFloat() else out.height.toFloat() / bounds.outHeight.coerceAtLeast(1)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0xFF212121.toInt()
        }
        // „Alle verdecken, eine erraten“: das Kennzeichen hängt an der gefragten Form.
        val hideAll = block.shapes.any { it.active && it.occludeInactive }
        for (s in block.shapes) {
            if (!s.active && !hideAll) continue
            fill.color = if (s.active) 0xFFFF8E8E.toInt() else 0xFFFFEBA2.toInt()
            val rect = RectF(s.left * sx, s.top * sy, (s.left + s.width) * sx, (s.top + s.height) * sy)
            if (s.active && block.revealed) {
                // Aufgedeckt: nur Umriss, damit man sieht, welche Stelle gefragt war.
                stroke.color = 0xFFFF8E8E.toInt(); stroke.strokeWidth = 4f
                shape(c, s, rect, sx, sy, stroke)
                continue
            }
            shape(c, s, rect, sx, sy, fill)
            stroke.color = 0xFF212121.toInt(); stroke.strokeWidth = 2f
            shape(c, s, rect, sx, sy, stroke)
        }
        out
    }

    private fun shape(c: Canvas, s: OcclusionShape, r: RectF, sx: Float, sy: Float, p: Paint) {
        when (s.kind) {
            "ellipse" -> c.drawOval(r, p)
            "polygon" -> if (s.points.size >= 3) {
                val path = Path()
                s.points.forEachIndexed { i, (x, y) ->
                    if (i == 0) path.moveTo(x * sx, y * sy) else path.lineTo(x * sx, y * sy)
                }
                path.close()
                c.drawPath(path, p)
            }
            else -> c.drawRect(r, p)
        }
    }

    private fun decode(file: File): Bitmap? = runCatching {
        if (file.name.endsWith(".svg", ignoreCase = true)) return@runCatching decodeSvg(file)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_PX || bounds.outHeight / (sample * 2) >= MAX_PX) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun decodeSvg(file: File): Bitmap {
        val svg = SVG.getFromInputStream(file.inputStream())
        val w = svg.documentWidth.takeIf { it > 0 } ?: svg.documentViewBox?.width() ?: MAX_PX.toFloat()
        val h = svg.documentHeight.takeIf { it > 0 } ?: svg.documentViewBox?.height() ?: MAX_PX.toFloat()
        val scale = MAX_PX / maxOf(w, h)
        val bmp = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        svg.setDocumentWidth(bmp.width.toFloat())
        svg.setDocumentHeight(bmp.height.toFloat())
        svg.renderToCanvas(Canvas(bmp))
        return bmp
    }
}

/** Spielt Kartenaudio nacheinander ab; ein neuer Aufruf bricht das laufende ab. */
class CardAudio(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var queue = ArrayDeque<File>()
    private var output = AudioOutput.BLUETOOTH_ONLY

    fun play(files: List<File>, output: AudioOutput) {
        stop()
        // Nie, oder „nur Bluetooth“ ohne verbundene Kopfhörer: stumm bleiben statt über den Uhr-Lautsprecher.
        if (output == AudioOutput.NEVER) return
        if (output == AudioOutput.BLUETOOTH_ONLY && bluetoothDevice() == null) return
        this.output = output
        queue = ArrayDeque(files)
        next()
    }

    /** Kopfhörer bevorzugen, wenn gewünscht und verbunden; sonst Uhr-Lautsprecher. */
    private fun preferredDevice(): AudioDeviceInfo? {
        val speaker = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        return bluetoothDevice() ?: if (output == AudioOutput.ALWAYS) speaker else null
    }

    fun bluetoothDevice(): AudioDeviceInfo? =
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }

    private fun next() {
        val f = queue.removeFirstOrNull() ?: return
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            runCatching {
                setDataSource(f.path)
                preferredDevice()?.let { setPreferredDevice(it) }
                setOnCompletionListener { release(); player = null; next() }
                setOnErrorListener { _, _, _ -> release(); player = null; next(); true }
                prepare()
                start()
            }.onFailure { release(); player = null; next() }
        }
    }

    fun stop() {
        queue.clear()
        player?.runCatching { stop(); release() }
        player = null
    }
}
