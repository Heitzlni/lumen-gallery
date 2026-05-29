package org.fossify.gallery.helpers

import android.content.Context
import android.provider.MediaStore
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * Walks MediaStore.Video, finds every video that doesn't already have a
 * cached scrub thumbnail strip, extracts one. Lets us match the
 * Google/Apple Photos pattern of "instant scrub on tap" — the strip is
 * built ahead of time while the user is doing something else.
 */
object ScrubPrefetcher {

    /**
     * Cap per session. Keeps the first run from churning the CPU for too
     * long on libraries with thousands of videos — the user can re-trigger
     * from Settings to process more.
     */
    private const val DEFAULT_MAX_VIDEOS_PER_SESSION = 200

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /**
     * Kick off a pre-cache pass on a background thread. [onProgress] is
     * called with (current, total) after each video; [onDone] with the
     * count of NEWLY-cached videos and whether the run was cancelled.
     */
    fun prefetchAll(
        context: Context,
        maxVideosPerSession: Int = DEFAULT_MAX_VIDEOS_PER_SESSION,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onDone: (cached: Int, cancelled: Boolean) -> Unit = { _, _ -> },
    ) {
        if (isRunning) {
            onDone(0, false)
            return
        }
        isRunning = true
        cancelled = false

        ensureBackgroundThread {
            // Low priority so this never fights the UI.
            try { Thread.currentThread().priority = Thread.MIN_PRIORITY } catch (_: Exception) {}

            var cached = 0
            try {
                val pending = enumerateVideos(context)
                    .filter { !ScrubThumbnailCache.isCached(context, it.path) }
                    .take(maxVideosPerSession)

                val total = pending.size
                pending.forEachIndexed { idx, info ->
                    if (cancelled) return@forEachIndexed
                    if (ScrubThumbnailCache.extractAndSave(context, info.path, info.durationMs)) {
                        cached++
                    }
                    onProgress(idx + 1, total)
                }
            } catch (_: Exception) {
            } finally {
                val wasCancelled = cancelled
                isRunning = false
                cancelled = false
                onDone(cached, wasCancelled)
            }
        }
    }

    /** Count of videos in MediaStore that haven't been cached yet. */
    fun pendingCount(context: Context): Int {
        return try {
            enumerateVideos(context).count { !ScrubThumbnailCache.isCached(context, it.path) }
        } catch (_: Exception) {
            0
        }
    }

    private data class VideoInfo(val path: String, val durationMs: Long)

    private fun enumerateVideos(context: Context): List<VideoInfo> {
        val list = ArrayList<VideoInfo>()
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.DURATION),
                null, null, null
            )?.use { c ->
                val pathIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val durIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                while (c.moveToNext()) {
                    val p = c.getString(pathIdx) ?: continue
                    val d = c.getLong(durIdx)
                    if (p.isNotEmpty() && d > 0L) list.add(VideoInfo(p, d))
                }
            }
        } catch (_: Exception) {
        }
        return list
    }
}
