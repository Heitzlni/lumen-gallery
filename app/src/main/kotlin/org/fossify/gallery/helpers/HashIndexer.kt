package org.fossify.gallery.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.extensions.imageHashDB
import org.fossify.gallery.models.ImageHash
import java.io.File
import java.io.FileInputStream

/**
 * Computes an 8x8 average-hash ("aHash") for every image in MediaStore and
 * stores it in `image_hashes`. Two images are likely near-duplicates if
 * their hashes have a hamming distance under [DupeFinder.SIMILARITY_THRESHOLD].
 *
 * Cheap enough to run on every photo in the library — the 8x8 thumbnail is
 * tiny and the hash is one Long per image.
 */
object HashIndexer {

    @Volatile
    private var cancelled = false

    @Volatile
    var isRunning: Boolean = false
        private set

    fun cancel() {
        cancelled = true
    }

    fun indexAll(
        context: Context,
        onProgress: (current: Int, total: Int) -> Unit,
        onDone: (indexedCount: Int, cancelled: Boolean) -> Unit,
    ) {
        if (isRunning) {
            onDone(0, false)
            return
        }
        isRunning = true
        cancelled = false

        ensureBackgroundThread {
            var indexed = 0
            try {
                val allPaths = enumerateImages(context)
                val pending = ArrayList<String>(allPaths.size)
                for (p in allPaths) {
                    if (!context.imageHashDB.hasFor(p)) pending.add(p)
                }
                val total = pending.size

                pending.forEachIndexed { idx, path ->
                    if (cancelled) return@forEachIndexed
                    val row = computeRow(path)
                    if (row != null) {
                        context.imageHashDB.insert(row)
                        indexed++
                    }
                    onProgress(idx + 1, total)
                }
            } catch (_: Exception) {
                // Stop quietly.
            } finally {
                val wasCancelled = cancelled
                isRunning = false
                cancelled = false
                onDone(indexed, wasCancelled)
            }
        }
    }

    private fun computeRow(path: String): ImageHash? {
        val file = File(path)
        if (!file.exists() || !file.canRead()) return null
        val phash = computeAHash(path) ?: return null
        return ImageHash(
            id = null,
            mediaPath = path,
            phash = phash,
            fileSize = file.length(),
            indexedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Decode the image heavily downsampled (the aHash only needs an 8x8
     * grayscale), resize to exactly 8x8, then compare each pixel against the
     * mean luma to build a 64-bit signature.
     */
    fun computeAHash(path: String): Long? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 64) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val full = FileInputStream(path).use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            val small = Bitmap.createScaledBitmap(full, 8, 8, true)
            if (small !== full) full.recycle()
            val pixels = IntArray(64)
            small.getPixels(pixels, 0, 8, 0, 0, 8, 8)
            small.recycle()

            val grays = IntArray(64) { i ->
                val px = pixels[i]
                ((px shr 16) and 0xFF) + ((px shr 8) and 0xFF) + (px and 0xFF)
            }
            val mean = grays.sum() / 64
            var hash = 0L
            for (i in 0 until 64) {
                if (grays[i] > mean) hash = hash or (1L shl i)
            }
            hash
        } catch (_: Exception) {
            null
        }
    }

    private fun enumerateImages(context: Context): List<String> {
        val paths = ArrayList<String>()
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, null,
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                while (c.moveToNext()) {
                    val p = c.getString(idx)
                    if (!p.isNullOrEmpty()) paths.add(p)
                }
            }
        } catch (_: Exception) {
        }
        return paths
    }
}
