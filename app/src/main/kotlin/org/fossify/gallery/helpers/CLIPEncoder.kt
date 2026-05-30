package org.fossify.gallery.helpers

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.File
import java.io.FileInputStream
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Loads the Xenova MobileCLIP-S0 ONNX models from `assets/clip/` and exposes
 * `encodeImage` / `encodeText`. Outputs are 512-dim L2-normalized [FloatArray]
 * so a plain dot product equals cosine similarity at search time.
 *
 * The vision and text sessions are heavy (~12 MB + 41 MB of weights loaded
 * into native memory), so this class is a per-process singleton and never
 * eagerly initialized — first `get()` after install pays the cold-start cost.
 */
class CLIPEncoder private constructor(
    private val env: OrtEnvironment,
    private val visionSession: OrtSession,
    private val textSession: OrtSession,
    private val visionInputName: String,
    private val textInputName: String,
    private val tokenizer: CLIPTokenizer,
) {

    fun encodeImage(bitmap: Bitmap): FloatArray {
        val pre = preprocessImage(bitmap)
        val shape = longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
        OnnxTensor.createTensor(env, pre, shape).use { tensor ->
            visionSession.run(mapOf(visionInputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val raw = result.get(0).value as Array<FloatArray>
                return l2Normalize(raw[0])
            }
        }
    }

    fun encodeText(text: String): FloatArray {
        val ids = tokenizer.encode(text)
        // MobileCLIP text encoders typically expect int64 input — accept either
        // by always sending a LongBuffer (ORT handles the type downcast safely).
        val buf = LongBuffer.allocate(ids.size)
        for (id in ids) buf.put(id.toLong())
        buf.rewind()
        val shape = longArrayOf(1, CLIPTokenizer.CONTEXT_LENGTH.toLong())
        OnnxTensor.createTensor(env, buf, shape).use { tensor ->
            textSession.run(mapOf(textInputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val raw = result.get(0).value as Array<FloatArray>
                return l2Normalize(raw[0])
            }
        }
    }

    /** Resize shortest-edge → 256, center-crop 256x256, divide by 255, NCHW. */
    private fun preprocessImage(bitmap: Bitmap): FloatBuffer {
        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)

        val w = src.width
        val h = src.height
        val scale = IMAGE_SIZE.toFloat() / minOf(w, h)
        val scaledW = (w * scale).toInt().coerceAtLeast(IMAGE_SIZE)
        val scaledH = (h * scale).toInt().coerceAtLeast(IMAGE_SIZE)
        val matrix = Matrix().apply { setScale(scale, scale) }
        val scaled = Bitmap.createBitmap(src, 0, 0, w, h, matrix, true)
        val cropX = (scaled.width - IMAGE_SIZE) / 2
        val cropY = (scaled.height - IMAGE_SIZE) / 2
        val cropped = Bitmap.createBitmap(
            scaled,
            cropX.coerceAtLeast(0),
            cropY.coerceAtLeast(0),
            IMAGE_SIZE,
            IMAGE_SIZE,
        )

        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        cropped.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

        // NCHW: [1, 3, H, W] — fill R plane, then G plane, then B plane.
        val buf = FloatBuffer.allocate(3 * IMAGE_SIZE * IMAGE_SIZE)
        val plane = IMAGE_SIZE * IMAGE_SIZE
        for (i in 0 until plane) {
            val px = pixels[i]
            buf.put(i, ((px shr 16) and 0xFF) / 255f)
            buf.put(plane + i, ((px shr 8) and 0xFF) / 255f)
            buf.put(2 * plane + i, (px and 0xFF) / 255f)
        }
        buf.rewind()

        if (scaled !== src) scaled.recycle()
        if (cropped !== scaled) cropped.recycle()
        if (src !== bitmap) src.recycle()
        return buf
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum.toDouble()).toFloat()
        if (norm < 1e-8f) return v
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / norm
        return out
    }

    companion object {
        const val IMAGE_SIZE = 256
        const val EMBED_DIM = 512

        @Volatile
        private var INSTANCE: CLIPEncoder? = null

        fun get(context: Context): CLIPEncoder {
            INSTANCE?.let { return it }
            synchronized(CLIPEncoder::class.java) {
                INSTANCE?.let { return it }
                val built = build(context)
                INSTANCE = built
                return built
            }
        }

        private fun build(context: Context): CLIPEncoder {
            val app = context.applicationContext
            val env = OrtEnvironment.getEnvironment()

            val opts = OrtSession.SessionOptions()
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Threading: keep it modest; users may be browsing while we index.
            opts.setIntraOpNumThreads(2)

            val visionBytes = app.assets.open("clip/vision_model.onnx").use { it.readBytes() }
            val textBytes = app.assets.open("clip/text_model.onnx").use { it.readBytes() }

            val vision = env.createSession(visionBytes, opts)
            val text = env.createSession(textBytes, opts)

            val visionInput = vision.inputNames.iterator().next()
            val textInput = text.inputNames.iterator().next()

            return CLIPEncoder(
                env = env,
                visionSession = vision,
                textSession = text,
                visionInputName = visionInput,
                textInputName = textInput,
                tokenizer = CLIPTokenizer.get(app),
            )
        }

        /**
         * Decode an image file at an appropriately downsampled size so we
         * don't blow RAM on a 50MP shot before we shrink it to 256x256
         * anyway. Returns null on unreadable file.
         */
        fun decodeForEmbedding(path: String): Bitmap? {
            val file = File(path)
            if (!file.exists() || !file.canRead()) return null
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                FileInputStream(file).use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                // Target ~2x model resolution before center-crop to avoid moiré.
                val target = IMAGE_SIZE * 2
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= target &&
                    bounds.outHeight / (sample * 2) >= target
                ) sample *= 2
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                FileInputStream(file).use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            } catch (_: Exception) {
                null
            }
        }
    }
}
