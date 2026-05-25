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

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FossifyGallery")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val uri: Uri? = try {
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
                FileInputStream(tempFile).use { input ->
                    input.copyTo(out)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val finalValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(uri, finalValues, null, null)
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

    private fun splitNameExt(name: String): Pair<String, String> {
        val idx = name.lastIndexOf('.')
        return if (idx <= 0) name to ""
        else name.substring(0, idx) to name.substring(idx + 1)
    }
}
