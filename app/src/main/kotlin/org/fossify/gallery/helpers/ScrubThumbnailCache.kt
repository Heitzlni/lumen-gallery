package org.fossify.gallery.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Disk-cached scrub thumbnail strips. VideoFragment reads from here for
 * instant scrub preview; ScrubPrefetcher writes here in the background.
 *
 * Cache key = SHA-1 of (path | size | mtime | version), so editing or
 * replacing a video naturally invalidates the cache. Layout matches what
 * VideoFragment already writes, so a prefetched strip is consumed
 * automatically the next time the user opens that video.
 */
object ScrubThumbnailCache {

    const val COUNT = 16
    const val MAX_DIM = 360

    fun cacheKeyFor(path: String): String {
        val f = File(path)
        val raw = "$path|${f.length()}|${f.lastModified()}|v${COUNT}d${MAX_DIM}"
        return try {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update(raw.toByteArray())
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            raw.hashCode().toString()
        }
    }

    fun cacheDirFor(context: Context, path: String): File {
        return File(context.cacheDir, "scrub_thumbs/${cacheKeyFor(path)}")
    }

    fun isCached(context: Context, path: String): Boolean {
        val dir = cacheDirFor(context, path)
        return dir.isDirectory && File(dir, "0.jpg").exists()
    }

    fun loadFromDisk(context: Context, path: String): Array<Bitmap?>? {
        val dir = cacheDirFor(context, path)
        if (!dir.isDirectory) return null
        val arr = arrayOfNulls<Bitmap>(COUNT)
        var loaded = 0
        for (i in 0 until COUNT) {
            val file = File(dir, "$i.jpg")
            if (file.exists()) {
                val bmp = try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Exception) { null }
                if (bmp != null) {
                    arr[i] = bmp
                    loaded++
                }
            }
        }
        return if (loaded > 0) arr else null
    }

    /**
     * Synchronously extract a 16-frame strip from the given video and save
     * it to the cache directory. Returns true on at least one saved frame.
     */
    fun extractAndSave(context: Context, path: String, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        if (isCached(context, path)) return true

        val dir = cacheDirFor(context, path)
        if (!dir.exists() && !dir.mkdirs()) return false

        val mmr = MediaMetadataRetriever()
        var saved = 0
        return try {
            mmr.setDataSource(path)
            val durationUs = durationMs * 1000L
            for (i in 0 until COUNT) {
                val timeUs = (durationUs * i) / COUNT
                val bitmap = extractFrame(mmr, timeUs) ?: continue
                try {
                    FileOutputStream(File(dir, "$i.jpg")).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos)
                    }
                    saved++
                } catch (_: Exception) {
                }
                bitmap.recycle()
            }
            saved > 0
        } catch (_: Exception) {
            false
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    private fun extractFrame(mmr: MediaMetadataRetriever, timeUs: Long): Bitmap? {
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                val scaled = mmr.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    MAX_DIM,
                    MAX_DIM
                )
                if (scaled != null) return scaled
            } catch (_: Exception) {
            }
        }
        val raw = try {
            mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } ?: return null
        if (raw.width <= MAX_DIM && raw.height <= MAX_DIM) return raw
        val maxDim = maxOf(raw.width, raw.height)
        val ratio = MAX_DIM.toFloat() / maxDim
        val newW = (raw.width * ratio).toInt().coerceAtLeast(1)
        val newH = (raw.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(raw, newW, newH, true)
        if (scaled !== raw) raw.recycle()
        return scaled
    }
}
