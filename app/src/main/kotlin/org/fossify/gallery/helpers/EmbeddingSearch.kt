package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.extensions.imageEmbeddingDB
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * In-memory cosine search over stored CLIP embeddings.
 *
 * The first call loads every (path, vec) row from Room into a packed
 * FloatArray once. Subsequent searches just embed the query (~50ms) and
 * dot-product against the cache (~5M flops for 10k photos → <50ms).
 *
 * Cache is invalidated when the row count changes — cheap proxy for
 * "user re-indexed" without needing observers.
 */
object EmbeddingSearch {

    private const val DIM = CLIPEncoder.EMBED_DIM
    // CLIP cosine similarities on quantized MobileCLIP-S0 cluster much
    // lower than full-precision CLIP — typical "actual match" scores end
    // up in the 0.15-0.30 range rather than 0.25-0.45. 0.20 was too
    // strict and dropped most real matches; 0.12 keeps obvious noise out
    // while letting "forest" actually surface forest-y photos.
    private const val SIM_THRESHOLD = 0.12f
    private const val MAX_RESULTS = 1000

    @Volatile
    private var cachedPaths: Array<String>? = null

    @Volatile
    private var cachedVectors: FloatArray? = null

    @Volatile
    private var cachedRowCount: Int = -1

    /**
     * Returns the media paths whose CLIP embedding is most cosine-similar to
     * the query text, sorted best-first. Empty if no embeddings indexed yet
     * or query is blank.
     */
    fun search(context: Context, query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        ensureCache(context)
        val paths = cachedPaths ?: return emptyList()
        val flat = cachedVectors ?: return emptyList()
        if (paths.isEmpty()) return emptyList()

        val encoder = try {
            CLIPEncoder.get(context)
        } catch (_: Exception) {
            return emptyList()
        }
        // OpenAI's CLIP zero-shot recipe wraps the query in "a photo of …".
        // Short bare nouns like "sky" land far from real photos in the
        // embedding space without it — the model was trained on caption-y
        // text. The prefix dramatically improves recall.
        val prompt = if (q.length <= 30 && !q.contains("photo")) "a photo of $q" else q
        val qv = try {
            encoder.encodeText(prompt)
        } catch (_: Exception) {
            return emptyList()
        }
        if (qv.size != DIM) return emptyList()

        // scores[i] = cosine(qv, flat[i*DIM .. i*DIM+DIM)). Both inputs are
        // already L2-normalized so a plain dot product is the cosine.
        val n = paths.size
        val scored = ArrayList<Pair<String, Float>>(n)
        var off = 0
        for (i in 0 until n) {
            var s = 0f
            for (k in 0 until DIM) s += qv[k] * flat[off + k]
            off += DIM
            if (s >= SIM_THRESHOLD) scored.add(paths[i] to s)
        }
        scored.sortByDescending { it.second }
        val take = minOf(MAX_RESULTS, scored.size)
        return ArrayList<String>(take).apply {
            for (i in 0 until take) add(scored[i].first)
        }
    }

    fun invalidate() {
        cachedPaths = null
        cachedVectors = null
        cachedRowCount = -1
    }

    @Synchronized
    private fun ensureCache(context: Context) {
        val dao = context.applicationContext.imageEmbeddingDB
        val live = try {
            dao.count()
        } catch (_: Exception) {
            return
        }
        if (live == cachedRowCount && cachedPaths != null) return

        val rows = try {
            dao.allForSearch()
        } catch (_: Exception) {
            return
        }
        val paths = Array(rows.size) { rows[it].mediaPath }
        val flat = FloatArray(rows.size * DIM)
        var off = 0
        for (r in rows) {
            val bb = ByteBuffer.wrap(r.vec).order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until DIM) flat[off + k] = bb.float
            off += DIM
        }
        cachedPaths = paths
        cachedVectors = flat
        cachedRowCount = live
    }
}
