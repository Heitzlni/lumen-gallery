package org.fossify.gallery.helpers

import android.content.Context
import android.provider.MediaStore
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.extensions.imageEmbeddingDB
import org.fossify.gallery.models.ImageEmbedding
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Walks MediaStore once and builds a CLIP image embedding for every photo
 * not yet present in `image_embeddings`. Cancellable; safe to re-run.
 *
 * Embedding is L2-normalized float32 stored as a little-endian ByteArray
 * (512 floats = 2048 bytes per image). 10k photos ≈ 20 MB on disk.
 */
object EmbeddingIndexer {

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
                val encoder = CLIPEncoder.get(context)
                val allPaths = enumerateImages(context)
                val pending = ArrayList<String>(allPaths.size)
                for (p in allPaths) {
                    if (!context.imageEmbeddingDB.hasFor(p)) pending.add(p)
                }
                val total = pending.size

                pending.forEachIndexed { idx, path ->
                    if (cancelled) return@forEachIndexed
                    val bitmap = CLIPEncoder.decodeForEmbedding(path)
                    if (bitmap != null) {
                        try {
                            val vec = encoder.encodeImage(bitmap)
                            context.imageEmbeddingDB.insert(
                                ImageEmbedding(
                                    id = null,
                                    mediaPath = path,
                                    vec = floatsToBytes(vec),
                                    indexedAt = System.currentTimeMillis(),
                                )
                            )
                            indexed++
                        } catch (_: Exception) {
                            // Skip broken images quietly so a single failure
                            // doesn't kill the whole indexing pass.
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    onProgress(idx + 1, total)
                }
            } catch (_: Exception) {
                // Model load failure or other fatal — bail with partial count.
            } finally {
                val wasCancelled = cancelled
                isRunning = false
                cancelled = false
                onDone(indexed, wasCancelled)
            }
        }
    }

    private fun enumerateImages(context: Context): List<String> {
        val paths = ArrayList<String>()
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null,
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

    private fun floatsToBytes(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) bb.putFloat(x)
        return bb.array()
    }
}
