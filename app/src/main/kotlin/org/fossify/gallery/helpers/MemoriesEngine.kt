package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.extensions.imageEmbeddingDB
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import kotlin.math.sqrt

/**
 * "This day, X years ago" memory generator. Uses CLIP embeddings to cluster
 * photos taken on the same calendar day (month + day) across past years and
 * surfaces only the LARGEST coherent cluster — so a meme posted on the same
 * day as a hiking trip doesn't end up in your memory of that hike.
 *
 * Caches today's result in [Config] so we only do the clustering work once
 * per day. The next launch on the same date is instant.
 */
object MemoriesEngine {

    private const val MIN_CLUSTER_PHOTOS = 2
    private const val CLUSTER_SIMILARITY_THRESHOLD = 0.45f
    private const val MAX_YEARS_BACK = 12
    private const val MAX_MEMORY_PHOTOS = 30

    data class Memory(
        val photos: List<String>,
        val yearsAgo: Int,
        val activityLabel: String,
    )

    /**
     * Returns today's memory (year + activity-coherent photo group) or null
     * if there's nothing worth showing. Skips work if a cached result for
     * today is already present in [Config].
     */
    fun computeToday(context: Context): Memory? {
        val cfg = Config.newInstance(context.applicationContext)
        val cachedDate = cfg.memoriesCachedDate
        val today = todayKey()
        if (cachedDate == today) {
            return cfg.memoriesCachedPhotos.takeIf { it.isNotEmpty() }?.let { paths ->
                Memory(
                    photos = paths.split('|').filter { File(it).exists() },
                    yearsAgo = cfg.memoriesCachedYearsAgo,
                    activityLabel = cfg.memoriesCachedLabel,
                )
            }?.takeIf { it.photos.size >= MIN_CLUSTER_PHOTOS }
        }

        val computed = computeMemoryFresh(context)
        // Write the cache regardless — even null result, so we don't recompute
        // on every onResume across the same day.
        cfg.memoriesCachedDate = today
        if (computed != null) {
            cfg.memoriesCachedPhotos = computed.photos.joinToString("|")
            cfg.memoriesCachedLabel = computed.activityLabel
            cfg.memoriesCachedYearsAgo = computed.yearsAgo
            cfg.memoriesCachedDismissed = false
        } else {
            cfg.memoriesCachedPhotos = ""
            cfg.memoriesCachedLabel = ""
            cfg.memoriesCachedYearsAgo = 0
        }
        return computed
    }

    private fun todayKey(): String {
        val cal = Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    private data class PhotoVec(val path: String, val vec: FloatArray, val year: Int)

    private fun computeMemoryFresh(context: Context): Memory? {
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH)
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val thisYear = cal.get(Calendar.YEAR)

        val rows = try {
            context.imageEmbeddingDB.allForSearch()
        } catch (_: Exception) {
            return null
        }
        if (rows.isEmpty()) return null

        // Filter by photo file mtime matching today's month + day in any past year
        // within [MAX_YEARS_BACK]. File mtime is a fair proxy for "date taken"
        // for the vast majority of photos and avoids per-file EXIF parsing.
        val byYearsAgo = HashMap<Int, MutableList<PhotoVec>>()
        val tmpCal = Calendar.getInstance()
        for (row in rows) {
            val file = File(row.mediaPath)
            if (!file.exists()) continue
            tmpCal.timeInMillis = file.lastModified()
            if (tmpCal.get(Calendar.MONTH) != month) continue
            if (tmpCal.get(Calendar.DAY_OF_MONTH) != day) continue
            val pYear = tmpCal.get(Calendar.YEAR)
            val yearsAgo = thisYear - pYear
            if (yearsAgo !in 1..MAX_YEARS_BACK) continue
            val vec = readFloatBlob(row.vec) ?: continue
            byYearsAgo.getOrPut(yearsAgo) { ArrayList() }
                .add(PhotoVec(row.mediaPath, vec, pYear))
        }
        if (byYearsAgo.isEmpty()) return null

        // For each candidate year, find its largest coherent cluster.
        var bestMemory: Memory? = null
        var bestScore = 0
        for ((yearsAgo, photos) in byYearsAgo) {
            if (photos.size < MIN_CLUSTER_PHOTOS) continue
            val cluster = pickLargestCluster(photos) ?: continue
            if (cluster.size < MIN_CLUSTER_PHOTOS) continue
            // Score is just the cluster size — tightness already filtered by
            // the similarity threshold.
            if (cluster.size > bestScore) {
                bestScore = cluster.size
                val sortedPaths = cluster.sortedByDescending { it.path } // newest filenames first
                val label = inferActivityLabel(context, meanVec(cluster.map { it.vec }))
                bestMemory = Memory(
                    photos = sortedPaths.take(MAX_MEMORY_PHOTOS).map { it.path },
                    yearsAgo = yearsAgo,
                    activityLabel = label,
                )
            }
        }
        return bestMemory
    }

    /** Greedy clustering: every unassigned photo seeds a cluster of itself
     *  plus all unassigned photos within [CLUSTER_SIMILARITY_THRESHOLD]. */
    private fun pickLargestCluster(photos: List<PhotoVec>): List<PhotoVec>? {
        if (photos.size < 2) return null
        val assigned = BooleanArray(photos.size)
        var best: List<Int>? = null
        for (i in photos.indices) {
            if (assigned[i]) continue
            val cluster = mutableListOf(i)
            assigned[i] = true
            for (j in i + 1 until photos.size) {
                if (assigned[j]) continue
                val sim = dot(photos[i].vec, photos[j].vec)
                if (sim > CLUSTER_SIMILARITY_THRESHOLD) {
                    cluster.add(j)
                    assigned[j] = true
                }
            }
            if (best == null || cluster.size > best.size) best = cluster
        }
        return best?.map { photos[it] }
    }

    private fun meanVec(vecs: List<FloatArray>): FloatArray {
        val dim = vecs.first().size
        val sum = FloatArray(dim)
        for (v in vecs) for (k in 0 until dim) sum[k] += v[k]
        for (k in 0 until dim) sum[k] /= vecs.size
        // L2-renormalize so cosine math stays valid.
        var norm = 0f
        for (k in 0 until dim) norm += sum[k] * sum[k]
        norm = sqrt(norm)
        if (norm > 1e-8f) for (k in 0 until dim) sum[k] /= norm
        return sum
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun readFloatBlob(bytes: ByteArray): FloatArray? {
        if (bytes.size % 4 != 0) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) out[i] = bb.float
        return out
    }

    // ------------------------------------------------------------------
    // Activity label inference via CLIP text embeddings of preset phrases.
    // ------------------------------------------------------------------

    private val PRESET_LABELS = listOf(
        "skiing or snowboarding on snow",
        "hiking on a forest trail",
        "in the mountains",
        "at the beach",
        "by a lake or river",
        "in a city street",
        "at a restaurant",
        "at a party with friends",
        "at a concert or live event",
        "at a wedding",
        "at a sports event",
        "at the gym",
        "with a pet animal",
        "selfie portrait",
        "nature landscape",
        "indoor gathering at home",
        "amusement park rides",
        "shopping",
        "road trip on a highway",
        "sunset or sunrise sky",
        "food on a plate",
    )

    @Volatile
    private var labelEmbeddings: List<Pair<String, FloatArray>>? = null

    private fun ensureLabels(context: Context) {
        if (labelEmbeddings != null) return
        synchronized(this) {
            if (labelEmbeddings != null) return
            try {
                val encoder = CLIPEncoder.get(context)
                labelEmbeddings = PRESET_LABELS.map { label ->
                    label to encoder.encodeText("a photo of $label")
                }
            } catch (_: Exception) {
                labelEmbeddings = emptyList()
            }
        }
    }

    private fun inferActivityLabel(context: Context, center: FloatArray): String {
        ensureLabels(context)
        val labels = labelEmbeddings ?: return "this day"
        if (labels.isEmpty()) return "this day"
        var best = "this day"
        var bestSim = 0.18f // floor — anything weaker isn't really an activity
        for ((label, vec) in labels) {
            val sim = dot(center, vec)
            if (sim > bestSim) {
                bestSim = sim
                best = label
            }
        }
        return best.replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * Fallback used by the Settings "Show any memory" button — finds the
     * best coherent cluster from ANY past month and day, not just today.
     * Useful for verifying the feature works without waiting for the
     * calendar to land on a day with past-year photos.
     */
    fun computeAnyMemory(context: Context): Memory? {
        val rows = try {
            context.imageEmbeddingDB.allForSearch()
        } catch (_: Exception) {
            return null
        }
        if (rows.size < MIN_CLUSTER_PHOTOS) return null

        // Bucket every CLIP-indexed photo by (year, month, day) from file mtime,
        // then look for the largest coherent cluster across all buckets that
        // are at least 30 days old (so it actually feels like a memory).
        val cal = Calendar.getInstance()
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - 30L * 24 * 60 * 60 * 1000
        val byDay = HashMap<String, MutableList<PhotoVec>>()
        for (row in rows) {
            val file = File(row.mediaPath)
            if (!file.exists()) continue
            val t = file.lastModified()
            if (t > thirtyDaysAgo) continue
            cal.timeInMillis = t
            val key = "%04d-%02d-%02d".format(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
            )
            val vec = readFloatBlob(row.vec) ?: continue
            byDay.getOrPut(key) { ArrayList() }
                .add(PhotoVec(row.mediaPath, vec, cal.get(Calendar.YEAR)))
        }
        if (byDay.isEmpty()) return null

        val nowYear = Calendar.getInstance().get(Calendar.YEAR)
        var bestMemory: Memory? = null
        var bestScore = 0
        for ((_, photos) in byDay) {
            if (photos.size < MIN_CLUSTER_PHOTOS) continue
            val cluster = pickLargestCluster(photos) ?: continue
            if (cluster.size < MIN_CLUSTER_PHOTOS) continue
            if (cluster.size > bestScore) {
                bestScore = cluster.size
                val yearsAgo = (nowYear - cluster.first().year).coerceAtLeast(0)
                val label = inferActivityLabel(context, meanVec(cluster.map { it.vec }))
                bestMemory = Memory(
                    photos = cluster.take(MAX_MEMORY_PHOTOS).map { it.path },
                    yearsAgo = yearsAgo,
                    activityLabel = label,
                )
            }
        }
        return bestMemory
    }

    fun diagnostics(context: Context): String {
        val rows = try {
            context.imageEmbeddingDB.allForSearch()
        } catch (_: Exception) {
            return "Embedding DB unavailable."
        }
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.MONTH) to cal.get(Calendar.DAY_OF_MONTH)
        val tmp = Calendar.getInstance()
        var liveCount = 0
        var todayMatches = 0
        for (row in rows) {
            val f = File(row.mediaPath)
            if (!f.exists()) continue
            liveCount++
            tmp.timeInMillis = f.lastModified()
            if (tmp.get(Calendar.MONTH) == today.first &&
                tmp.get(Calendar.DAY_OF_MONTH) == today.second
            ) todayMatches++
        }
        return "CLIP-indexed photos: ${rows.size}\n" +
            "Files still present: $liveCount\n" +
            "Photos taken on today's month/day from past years: $todayMatches\n" +
            "Memory threshold: ≥$MIN_CLUSTER_PHOTOS coherent photos within ${MAX_YEARS_BACK}y."
    }
}
