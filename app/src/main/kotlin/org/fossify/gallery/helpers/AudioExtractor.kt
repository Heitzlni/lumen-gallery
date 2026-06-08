package org.fossify.gallery.helpers

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * Strip the audio track out of a video file and write it to the public
 * Music/Fossify-Gallery directory.
 *
 * Implemented via Media3 [Transformer] with [EditedMediaItem.Builder.setRemoveVideo]:
 * the muxer just re-packages the existing audio bytes into an .m4a container,
 * no re-encoding — extraction of a 2 GB video takes a couple of seconds.
 *
 * Supports a single source or a batch; [extractBatch] runs them sequentially
 * because Media3's Transformer instance is single-shot.
 */
object AudioExtractor {

    data class Result(val sourcePath: String, val outputPath: String?, val errorMessage: String?)

    private fun outputDir(context: Context): File {
        // Public Music/Fossify-Gallery so the user can find what was extracted
        // from any music player on the device.
        val musicRoot = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MUSIC
        )
        val dir = File(musicRoot, "Fossify-Gallery")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun deriveOutputFile(context: Context, sourcePath: String): File {
        val srcName = File(sourcePath).nameWithoutExtension
        var candidate = File(outputDir(context), "${srcName}.m4a")
        var i = 2
        while (candidate.exists()) {
            candidate = File(outputDir(context), "${srcName} ($i).m4a")
            i++
        }
        return candidate
    }

    fun extractOne(
        context: Context,
        sourcePath: String,
        onDone: (Result) -> Unit,
    ) {
        val source = File(sourcePath)
        if (!source.exists() || !source.canRead()) {
            onDone(Result(sourcePath, null, "source not readable"))
            return
        }
        val output = deriveOutputFile(context, sourcePath)
        val mediaItem = MediaItem.fromUri(Uri.fromFile(source))
        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .build()
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    // Make it visible to other media-aware apps right away.
                    MediaScannerConnection.scanFile(
                        context, arrayOf(output.absolutePath), arrayOf("audio/mp4"), null
                    )
                    onDone(Result(sourcePath, output.absolutePath, null))
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (output.exists()) output.delete()
                    onDone(Result(sourcePath, null, exportException.message ?: "export failed"))
                }
            })
            .build()
        try {
            transformer.start(edited, output.absolutePath)
        } catch (e: Exception) {
            if (output.exists()) output.delete()
            onDone(Result(sourcePath, null, e.message ?: "transformer start failed"))
        }
    }

    /**
     * Run [extractOne] in sequence over [sources]. The caller's [onProgress]
     * fires after each file completes; [onAllDone] fires once with the
     * aggregated list of results.
     */
    fun extractBatch(
        context: Context,
        sources: List<String>,
        onProgress: (current: Int, total: Int, result: Result) -> Unit,
        onAllDone: (results: List<Result>) -> Unit,
    ) {
        val total = sources.size
        if (total == 0) {
            onAllDone(emptyList())
            return
        }
        val results = ArrayList<Result>(total)
        fun runNext(i: Int) {
            if (i >= total) {
                onAllDone(results)
                return
            }
            extractOne(context, sources[i]) { result ->
                results.add(result)
                onProgress(i + 1, total, result)
                // Transformer touches MainLooper internally; chain on it.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runNext(i + 1)
                }
            }
        }
        // Transformer must be created and started on the main thread.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runNext(0)
        }
    }
}
