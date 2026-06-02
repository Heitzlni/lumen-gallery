package org.fossify.gallery.helpers

/**
 * Session-only video playback queue. Lives in memory for the lifetime of
 * the app process — exists so the user can hit "Play all" / "Shuffle" in
 * an album and the player auto-advances through the videos.
 *
 * Behavior contract (from the user's flow):
 *   - When the current video ends, [next] returns the next path or null
 *     when we hit the end.
 *   - When the user opens a video that ISN'T in the queue, the caller is
 *     expected to [clear] — that disables auto-advance for the unrelated
 *     clip the user just chose.
 *   - Shuffle = list pre-permuted once, then walked in order; each video
 *     plays exactly once until exhausted.
 */
object PlaybackQueue {

    @Volatile
    private var ordered: List<String> = emptyList()

    @Volatile
    private var idx: Int = -1

    @Volatile
    var shuffle: Boolean = false
        private set

    val isActive: Boolean get() = ordered.isNotEmpty()

    /**
     * Replace the queue with [paths], optionally shuffled. The currently
     * playing path (if it's in the new list) is what the playback head
     * is positioned at, so [next] continues from there.
     */
    fun setQueue(paths: List<String>, shuffle: Boolean, startWith: String? = null) {
        ordered = if (shuffle) paths.shuffled() else paths
        idx = if (startWith != null) ordered.indexOf(startWith) else -1
        this.shuffle = shuffle
    }

    /**
     * Returns the next path in the queue after [currentPath]. If
     * [currentPath] is null or not in the queue, returns the first item.
     * Returns null when the queue is empty or we've reached the end.
     */
    fun next(currentPath: String?): String? {
        if (ordered.isEmpty()) return null
        val curIdx = currentPath?.let { ordered.indexOf(it) } ?: idx
        val nextIdx = curIdx + 1
        if (nextIdx !in ordered.indices) return null
        idx = nextIdx
        return ordered[nextIdx]
    }

    fun contains(path: String): Boolean = path in ordered

    fun clear() {
        ordered = emptyList()
        idx = -1
        shuffle = false
    }
}
