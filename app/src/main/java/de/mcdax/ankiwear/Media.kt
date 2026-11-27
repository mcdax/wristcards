package de.mcdax.ankiwear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.content.res.AssetManager
import java.security.MessageDigest
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

private val SVG_FILTER = Regex("""<(?:[\w-]+:)?filter\b""", RegexOption.IGNORE_CASE)

/**
 * Loads card images sparingly: the watch runs 32-bit with little RAM, so always
 * at most [MAX_PX] in size and with a small cache.
 */
object CardImages {
    private const val MAX_PX = 720
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** On memory pressure (onTrimMemory) clear the cache; images can be reloaded at any time. */
    fun trim() = cache.evictAll()

    /** If a file changes under the same name (media sync), the old cache entry no longer fits. */
    private fun fingerprint(file: File) = "${file.path}|${file.length()}|${file.lastModified()}"

    suspend fun load(file: File, assets: AssetManager): Bitmap? = withContext(Dispatchers.IO) {
        val key = fingerprint(file)
        cache.get(key) ?: decode(file, assets)?.also { cache.put(key, it) }
    }

    /**
     * Image with an IOE mask (SVG at image size) on top. Without a readable mask there is no image:
     * the unmasked image would show the answer on the question.
     */
    suspend fun loadMasked(image: File, mask: File?): Bitmap? = withContext(Dispatchers.IO) {
        if (mask == null) return@withContext null
        val key = fingerprint(image) + "|" + fingerprint(mask)
        cache.get(key)?.let { return@withContext it }
        val out = decode(image, mutable = true) ?: return@withContext null
        val drawn = runCatching {
            val svg = mask.inputStream().use { SVG.getFromInputStream(it) }
            svg.setDocumentWidth(out.width.toFloat())
            svg.setDocumentHeight(out.height.toFloat())
            svg.renderToCanvas(Canvas(out))
        }
        if (drawn.isFailure) {
            if (BuildConfig.DEBUG) android.util.Log.w("CardImages", "Mask not readable", drawn.exceptionOrNull())
            out.recycle()
            return@withContext null
        }
        cache.put(key, out)
        out
    }

    /** Image with native IO shapes; coordinates are relative (0..1) or pixels of the original. */
    suspend fun loadOccluded(image: File, block: Block.OccludedImage): Bitmap? = withContext(Dispatchers.IO) {
        // Shapes and side belong to the key: question and answer draw differently.
        val key = fingerprint(image) + "|" + block.shapes.hashCode() + "|" + block.revealed
        cache.get(key)?.let { return@withContext it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.path, bounds)
        val out = decode(image, mutable = true) ?: return@withContext null
        val c = Canvas(out)
        // Pixel values refer to the original image, we draw into the downscaled one.
        // Polygons only have points (left/top/width/height are 0), so check the points too.
        val relative = block.shapes.all { s ->
            s.left <= 1f && s.top <= 1f && s.width <= 1f && s.height <= 1f && s.points.all { (x, y) -> x <= 1f && y <= 1f }
        }
        val sx = if (relative) out.width.toFloat() else out.width.toFloat() / bounds.outWidth.coerceAtLeast(1)
        val sy = if (relative) out.height.toFloat() else out.height.toFloat() / bounds.outHeight.coerceAtLeast(1)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0xFF212121.toInt()
        }
        // "Hide all, guess one": the marker is attached to the asked shape.
        val hideAll = block.shapes.any { it.active && it.occludeInactive }
        for (s in block.shapes) {
            if (!s.active && !hideAll) continue
            fill.color = if (s.active) 0xFFFF8E8E.toInt() else 0xFFFFEBA2.toInt()
            val rect = RectF(s.left * sx, s.top * sy, (s.left + s.width) * sx, (s.top + s.height) * sy)
            if (s.active && block.revealed) {
                // Revealed: outline only, so you can see which spot was asked.
                stroke.color = 0xFFFF8E8E.toInt(); stroke.strokeWidth = 4f
                shape(c, s, rect, sx, sy, stroke)
                continue
            }
            shape(c, s, rect, sx, sy, fill)
            stroke.color = 0xFF212121.toInt(); stroke.strokeWidth = 2f
            shape(c, s, rect, sx, sy, stroke)
        }
        cache.put(key, out)
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

    /**
     * Decodes at most [MAX_PX] in size: first roughly via inSampleSize (saves memory while reading),
     * then scaled down exactly. [mutable] if it is drawn on (saves a copy).
     */
    private fun decode(file: File, assets: AssetManager? = null, mutable: Boolean = false): Bitmap? = runCatching {
        if (file.name.endsWith(".svg", ignoreCase = true)) return@runCatching decodeSvg(file, assets)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_PX || bounds.outHeight / (sample * 2) >= MAX_PX) sample *= 2
        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inMutable = mutable
        }) ?: return@runCatching null
        val scale = MAX_PX.toFloat() / maxOf(raw.width, raw.height)
        if (scale >= 1f) return@runCatching raw
        val scaled = Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), true)
        raw.recycle()
        // createScaledBitmap returns a mutable bitmap if the source bitmap was mutable.
        if (mutable && !scaled.isMutable) scaled.copy(Bitmap.Config.ARGB_8888, true).also { scaled.recycle() } else scaled
    }.onFailure {
        if (BuildConfig.DEBUG) android.util.Log.w("CardImages", "Image could not be rendered", it)
    }.getOrNull()

    private fun decodeSvg(file: File, assets: AssetManager?): Bitmap {
        val source = file.readBytes()
        if (file.name.contains("-blur", ignoreCase = true) &&
            SVG_FILTER.containsMatchIn(source.toString(Charsets.UTF_8))) {
            // AndroidSVG cannot draw filters. For the three known UG media files
            // a filter-faithful rasterization exists; use it only for exactly identical bytes.
            val hash = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }
            val raster = runCatching {
                assets?.open("svg-filters/$hash.png")?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (raster != null) return raster
            // Don't silently draw without blur: that could reveal the answer.
            error("SVG filter without matching rasterization")
        }
        val svg = source.inputStream().use { SVG.getFromInputStream(it) }
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

/**
 * Plays card audio one after another; a new call cancels the running one.
 * All calls come from the main thread; the files are prepared asynchronously so revealing doesn't stutter.
 */
class CardAudio(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var queue = ArrayDeque<File>()
    private var output = AudioOutput.BLUETOOTH_ONLY

    // "Bluetooth only": if the headphones are disconnected, stop immediately instead of switching to the speaker.
    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (output == AudioOutput.BLUETOOTH_ONLY && player != null && bluetoothDevice() == null) stop()
        }
    }
    private var callbackRegistered = false

    fun play(files: List<File>, output: AudioOutput) {
        stop()
        // Never, or "Bluetooth only" without connected headphones: stay silent instead of using the watch speaker.
        if (output == AudioOutput.NEVER) return
        if (output == AudioOutput.BLUETOOTH_ONLY && bluetoothDevice() == null) return
        this.output = output
        if (!callbackRegistered) {
            audioManager?.registerAudioDeviceCallback(deviceCallback, null)
            callbackRegistered = true
        }
        queue = ArrayDeque(files)
        next()
    }

    /** Prefer headphones if wanted and connected; otherwise the watch speaker. */
    private fun preferredDevice(): AudioDeviceInfo? {
        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        return devices.firstOrNull(::isBluetooth)
            ?: if (output == AudioOutput.ALWAYS) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } else null
    }

    fun bluetoothDevice(): AudioDeviceInfo? =
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().firstOrNull(::isBluetooth)

    private fun isBluetooth(d: AudioDeviceInfo) =
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || d.type == AudioDeviceInfo.TYPE_BLE_HEADSET

    private fun next() {
        val f = queue.removeFirstOrNull() ?: return
        val device = preferredDevice()
        // The headphones may be gone between two tracks.
        if (output == AudioOutput.BLUETOOTH_ONLY && device == null) {
            stop()
            return
        }
        val p = MediaPlayer()
        player = p
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setDataSource(f.path)
            device?.let { p.setPreferredDevice(it) }
            // Callbacks of a superseded player (stop/new call) must not trigger anything anymore.
            p.setOnPreparedListener { if (player === p) p.start() }
            p.setOnCompletionListener { advance(p) }
            p.setOnErrorListener { _, _, _ -> advance(p); true }
            p.prepareAsync()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.w("CardAudio", "Audio not playable: ${f.name}", e)
            advance(p)
        }
    }

    /** Releases [p] and plays the next track, if [p] was still the current player. */
    private fun advance(p: MediaPlayer) {
        p.release()
        if (player === p) {
            player = null
            next()
        }
    }

    fun stop() {
        queue.clear()
        val p = player ?: return
        player = null
        try {
            if (p.isPlaying) p.stop()
        } catch (_: IllegalStateException) {
            // Still preparing: stop() is not allowed then, release() is enough.
        } finally {
            p.release()
        }
    }

    /** Clean up for good when the study view goes away. */
    fun release() {
        stop()
        if (callbackRegistered) {
            audioManager?.unregisterAudioDeviceCallback(deviceCallback)
            callbackRegistered = false
        }
    }
}
