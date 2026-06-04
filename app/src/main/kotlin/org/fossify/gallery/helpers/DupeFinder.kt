package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.extensions.imageHashDB
import org.fossify.gallery.models.ImageHash
import java.io.File

/**
 * Groups already-indexed images by perceptual-hash similarity.
 *
 * For typical phone libraries (under ~30k photos) we just brute-force
 * pairwise hamming distance (XOR + bitCount, single Long op per pair).
 * 10k photos = 100M comparisons ≈ well under a second on any modern phone.
 * Anything denser than this would need LSH / locality-sensitive hashing,
 * which we can add later if the user complains about scan latency.
 */
object DupeFinder {

    /** Two hashes with hamming distance ≤ this are treated as the same image. */
    const val SIMILARITY_THRESHOLD = 5

    data class Group(
        /** Paths in the group, sorted newest-first by file mtime. */
        val paths: List<String>,
        /** Total bytes of the duplicates (excluding the newest one we'd keep). */
        val reclaimableBytes: Long,
    )

    /**
     * Walk every indexed image and union-find them into similarity groups.
     * Only returns groups of size ≥ 2. Sorted by reclaimable space (largest
     * first) so the user sees the biggest wins at the top.
     */
    fun findGroups(context: Context): List<Group> {
        val all = try {
            context.imageHashDB.all()
        } catch (_: Exception) {
            return emptyList()
        }
        if (all.size < 2) return emptyList()

        val n = all.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            // path compression
            var cur = x
            while (parent[cur] != r) {
                val nxt = parent[cur]
                parent[cur] = r
                cur = nxt
            }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        for (i in 0 until n) {
            val hi = all[i].phash
            for (j in i + 1 until n) {
                val dist = java.lang.Long.bitCount(hi xor all[j].phash)
                if (dist <= SIMILARITY_THRESHOLD) union(i, j)
            }
        }

        val byRoot = HashMap<Int, MutableList<ImageHash>>()
        for (i in 0 until n) {
            val r = find(i)
            byRoot.getOrPut(r) { ArrayList() }.add(all[i])
        }

        val groups = ArrayList<Group>(byRoot.size)
        for (members in byRoot.values) {
            if (members.size < 2) continue
            // Filter out rows whose backing file is gone.
            val live = members.filter { File(it.mediaPath).exists() }
            if (live.size < 2) continue
            val sorted = live.sortedByDescending { File(it.mediaPath).lastModified() }
            val paths = sorted.map { it.mediaPath }
            val reclaimable = sorted.drop(1).sumOf { it.fileSize }
            groups.add(Group(paths, reclaimable))
        }
        groups.sortByDescending { it.reclaimableBytes }
        return groups
    }
}
