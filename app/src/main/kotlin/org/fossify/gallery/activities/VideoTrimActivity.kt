package org.fossify.gallery.activities

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityVideoTrimBinding
import org.fossify.gallery.helpers.PATH
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

@UnstableApi
class VideoTrimActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityVideoTrimBinding::inflate)

    private var sourcePath: String = ""
    private var sourceDurationMs: Long = 0L
    private var startMs: Long = 0L
    private var endMs: Long = 0L
    private var player: ExoPlayer? = null
    private var isExporting: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        sourcePath = intent.getStringExtra(PATH).orEmpty()
        if (sourcePath.isEmpty() || !File(sourcePath).exists()) {
            toast(org.fossify.commons.R.string.unknown_error_occurred)
            finish()
            return
        }

        binding.videoTrimToolbar.title = getString(R.string.trim_video)

        setupEdgeToEdge(
            padTopSystem = listOf(binding.videoTrimAppbar),
            padBottomSystem = listOf(binding.root)
        )

        binding.videoTrimToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.save_trim) {
                if (!isExporting) startTrim()
                return@setOnMenuItemClickListener true
            }
            false
        }

        setupPlayer()
        setupRangeSlider()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.videoTrimAppbar, NavigationIcon.Arrow)
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    private fun setupPlayer() {
        val exoPlayer = ExoPlayer.Builder(this).build()
        binding.videoTrimPlayer.player = exoPlayer
        val mediaItem = MediaItem.fromUri(Uri.fromFile(File(sourcePath)))
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = false
        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_READY && sourceDurationMs == 0L) {
                    sourceDurationMs = exoPlayer.duration.coerceAtLeast(1L)
                    startMs = 0L
                    endMs = sourceDurationMs
                    updateLabels()
                }
            }
        })
        player = exoPlayer
    }

    private fun setupRangeSlider() {
        binding.videoTrimRange.values = listOf(0.0f, 1.0f)
        binding.videoTrimRange.addOnChangeListener { slider, _, _ ->
            val values = slider.values
            val total = if (sourceDurationMs > 0) sourceDurationMs else 1L
            val newStart = (values[0] * total).toLong()
            val newEnd = (values[1] * total).toLong().coerceAtLeast(newStart + 100L)
            if (newStart != startMs) {
                startMs = newStart
                player?.seekTo(startMs)
            } else if (newEnd != endMs) {
                endMs = newEnd
                player?.seekTo((endMs - 200L).coerceAtLeast(0L))
            }
            updateLabels()
        }
    }

    private fun updateLabels() {
        binding.videoTrimStartLabel.text = formatMillis(startMs)
        binding.videoTrimEndLabel.text = formatMillis(endMs)
        val selected = endMs - startMs
        binding.videoTrimDurationLabel.text =
            getString(R.string.trim_selection_length, formatMillis(selected))
    }

    private fun formatMillis(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    private fun startTrim() {
        val duration = sourceDurationMs
        if (duration <= 0) {
            toast(org.fossify.commons.R.string.unknown_error_occurred)
            return
        }
        val safeStart = startMs.coerceIn(0L, duration - 100L)
        val safeEnd = endMs.coerceIn(safeStart + 100L, duration)
        if (safeEnd - safeStart < 200L) {
            toast(R.string.trim_too_short)
            return
        }

        isExporting = true
        binding.videoTrimProgressOverlay.beVisible()
        player?.playWhenReady = false

        val tempOut = File(cacheDir, "trim_${System.currentTimeMillis()}.mp4").apply {
            if (exists()) delete()
        }

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(File(sourcePath)))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(safeStart)
                    .setEndPositionMs(safeEnd)
                    .build()
            )
            .build()

        val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

        val transformer = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    publishToGallery(tempOut)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    handleTrimError(exportException.message ?: "export failed")
                    tempOut.delete()
                }
            })
            .build()

        try {
            transformer.start(editedMediaItem, tempOut.absolutePath)
        } catch (e: Exception) {
            handleTrimError(e.message ?: "transformer start failed")
            tempOut.delete()
        }
    }

    private fun handleTrimError(message: String) {
        isExporting = false
        binding.videoTrimProgressOverlay.beGone()
        toast(getString(R.string.trim_failed, message))
    }

    private fun publishToGallery(tempFile: File) {
        val originalName = sourcePath.getFilenameFromPath()
        val (baseName, ext) = splitNameExt(originalName)
        val outName = "${baseName}_trimmed.${ext.ifEmpty { "mp4" }}"

        // Carry the original "shot at" timestamp forward — but offset the
        // trimmed copy by +1 second so it sorts immediately AFTER the
        // original in the camera roll (instead of getting bucketed at the
        // top of that day's section because of how date_taken collisions
        // get resolved).
        val rawSourceDate = extractSourceDateMs(sourcePath)
        val sourceDate = if (rawSourceDate > 0L) rawSourceDate + 1000L else 0L

        // Drop the trimmed copy next to the source so it shows up in the
        // same folder the user opened it from. Falling back to
        // Movies/FossifyGallery only when the source isn't reachable from
        // a writable directory or sits outside the standard media roots
        // (Q+ MediaStore.RELATIVE_PATH only accepts DCIM / Movies /
        // Pictures roots for Video.Media inserts).
        val sourceParentFile = File(sourcePath).parentFile
        val sourceParent = sourceParentFile?.absolutePath

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            publishViaDirectWrite(tempFile, sourceParentFile, outName, sourceDate)
            return
        }

        val relativePath = sourceParent?.let { computeRelativePath(it) }
            ?: "Movies/FossifyGallery/"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
            if (sourceDate > 0L) {
                put(MediaStore.MediaColumns.DATE_TAKEN, sourceDate)
                put(MediaStore.MediaColumns.DATE_MODIFIED, sourceDate / 1000L)
                put(MediaStore.MediaColumns.DATE_ADDED, sourceDate / 1000L)
            }
        }

        val resolver = contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val uri: Uri? = try {
            resolver.insert(collection, values)
        } catch (_: Exception) {
            null
        }

        if (uri == null) {
            // The targeted folder rejected the insert (e.g. source lived
            // under Download/ or another non-media root). Fall back to the
            // bundled FossifyGallery folder so the trim is still saved.
            publishToFallbackFolder(tempFile, outName, sourceDate)
            return
        }

        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(tempFile).use { input ->
                    input.copyTo(out)
                }
            }
            val finalValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(uri, finalValues, null, null)

            // Same belt-and-suspenders the vault restore uses: force the
            // filesystem mtime so apps that rank by file time match those
            // that read MediaStore DATE_TAKEN.
            //
            // Also push DATE_TAKEN / DATE_MODIFIED / DATE_ADDED AGAIN in a
            // separate update AFTER IS_PENDING flips — on some Android
            // builds MediaStore resets these to "now" during the IS_PENDING
            // -> 0 transition, which is why the trimmed clip kept landing
            // at the top of the day.
            if (sourceDate > 0L) {
                try {
                    val dateUpdate = ContentValues().apply {
                        put(MediaStore.MediaColumns.DATE_TAKEN, sourceDate)
                        put(MediaStore.MediaColumns.DATE_MODIFIED, sourceDate / 1000L)
                        put(MediaStore.MediaColumns.DATE_ADDED, sourceDate / 1000L)
                    }
                    resolver.update(uri, dateUpdate, null, null)
                } catch (_: Exception) {
                }
                try {
                    val proj = arrayOf(MediaStore.MediaColumns.DATA)
                    resolver.query(uri, proj, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val path = c.getString(0)
                            if (!path.isNullOrEmpty()) {
                                File(path).setLastModified(sourceDate)
                                // Trigger a MediaScanner rescan so Fossify's
                                // next directory load picks up the file with
                                // the corrected dates instead of whatever
                                // MediaStore inserted initially.
                                android.media.MediaScannerConnection.scanFile(
                                    applicationContext, arrayOf(path), null, null
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            tempFile.delete()
            isExporting = false
            runOnUiThread {
                binding.videoTrimProgressOverlay.beGone()
                toast(getString(R.string.trim_saved_as, outName))
                finish()
            }
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            handleTrimError(e.message ?: "could not write output")
            tempFile.delete()
        }
    }

    /**
     * Resolves an absolute parent directory to a MediaStore RELATIVE_PATH
     * (e.g. "/storage/emulated/0/DCIM/Camera" -> "DCIM/Camera/"). Returns
     * null when the directory isn't under primary external storage.
     *
     * We don't whitelist top-level dirs anymore -- MediaStore.Video accepts
     * any path under external storage, not just the canonical media roots.
     * That matters for WhatsApp / Telegram / Download / etc. source videos
     * whose owning folder is not DCIM/Movies/Pictures. If MediaStore later
     * rejects the insert (which can happen on some OEM builds), the caller
     * falls back to Movies/FossifyGallery.
     */
    private fun computeRelativePath(absoluteParent: String): String? {
        val externalRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
        if (!absoluteParent.startsWith("$externalRoot/")) return null
        val rel = absoluteParent.substring(externalRoot.length + 1)
        if (rel.isEmpty()) return null
        return if (rel.endsWith('/')) rel else "$rel/"
    }

    private fun publishToFallbackFolder(tempFile: File, outName: String, sourceDate: Long) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FossifyGallery/")
            put(MediaStore.Video.Media.IS_PENDING, 1)
            if (sourceDate > 0L) {
                put(MediaStore.MediaColumns.DATE_TAKEN, sourceDate)
                put(MediaStore.MediaColumns.DATE_MODIFIED, sourceDate / 1000L)
                put(MediaStore.MediaColumns.DATE_ADDED, sourceDate / 1000L)
            }
        }
        val resolver = contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try {
            resolver.insert(collection, values)
        } catch (_: Exception) {
            null
        }
        if (uri == null) {
            handleTrimError("could not create output")
            tempFile.delete()
            return
        }
        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(tempFile).use { input -> input.copyTo(out) }
            }
            val finalValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(uri, finalValues, null, null)
            tempFile.delete()
            isExporting = false
            runOnUiThread {
                binding.videoTrimProgressOverlay.beGone()
                toast(getString(R.string.trim_saved_as, outName))
                finish()
            }
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            handleTrimError(e.message ?: "could not write output")
            tempFile.delete()
        }
    }

    /**
     * Pre-Q path: MediaStore.RELATIVE_PATH is ignored; the file system is
     * the source of truth. Copy the temp file next to the source and let
     * MediaScanner pick it up.
     */
    private fun publishViaDirectWrite(
        tempFile: File,
        sourceParent: File?,
        outName: String,
        sourceDate: Long,
    ) {
        val targetDir = sourceParent
            ?: File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "FossifyGallery")
        if (!targetDir.exists()) targetDir.mkdirs()
        val outFile = File(targetDir, outName)
        try {
            FileInputStream(tempFile).use { input ->
                FileOutputStream(outFile).use { out -> input.copyTo(out) }
            }
            if (sourceDate > 0L) outFile.setLastModified(sourceDate)
            android.media.MediaScannerConnection.scanFile(
                applicationContext, arrayOf(outFile.absolutePath), null, null
            )
            tempFile.delete()
            isExporting = false
            runOnUiThread {
                binding.videoTrimProgressOverlay.beGone()
                toast(getString(R.string.trim_saved_as, outName))
                finish()
            }
        } catch (e: Exception) {
            try { outFile.delete() } catch (_: Exception) {}
            handleTrimError(e.message ?: "could not write output")
            tempFile.delete()
        }
    }

    private fun splitNameExt(name: String): Pair<String, String> {
        val idx = name.lastIndexOf('.')
        return if (idx <= 0) name to ""
        else name.substring(0, idx) to name.substring(idx + 1)
    }

    private fun extractSourceDateMs(path: String): Long {
        // MP4/MOV containers expose a creation date through
        // METADATA_KEY_DATE, typically ISO-8601 like "20240501T123456.000Z".
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val raw = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)
            val parsed = if (!raw.isNullOrBlank()) parseMetadataDate(raw) else 0L
            if (parsed > 0L) parsed else File(path).lastModified()
        } catch (_: Exception) {
            try { File(path).lastModified() } catch (_: Exception) { 0L }
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun parseMetadataDate(raw: String): Long {
        val formats = listOf(
            "yyyyMMdd'T'HHmmss.SSS'Z'",
            "yyyyMMdd'T'HHmmss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (f in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(f, Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                return sdf.parse(raw)?.time ?: continue
            } catch (_: Exception) {
            }
        }
        return 0L
    }
}
