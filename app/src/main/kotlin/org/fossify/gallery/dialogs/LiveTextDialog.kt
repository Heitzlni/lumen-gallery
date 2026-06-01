package org.fossify.gallery.dialogs

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.databinding.DialogLiveTextBinding
import org.fossify.gallery.extensions.imageTextDB
import org.fossify.gallery.models.ImageText
import java.io.File

/**
 * Show the recognized text inside a photo so the user can drag-select &
 * copy it. Uses the cached `image_texts` row if the photo's been indexed,
 * otherwise runs ML Kit Text Recognition live (~1s on a phone CPU).
 *
 * When OCR runs live and the photo is on local storage, the result is also
 * persisted so subsequent long-presses are instant.
 */
class LiveTextDialog(
    private val activity: Activity,
    private val mediaPath: String,
) {

    private var dialog: AlertDialog? = null
    private val binding = DialogLiveTextBinding.inflate(activity.layoutInflater)

    init {
        binding.liveTextStatus.text = activity.getString(R.string.live_text_scanning)
        binding.liveTextStatus.visibility = android.view.View.VISIBLE
        binding.liveTextBody.text = ""

        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.live_text_copy_all) { _, _ ->
                copyAll()
            }
            .setNegativeButton(org.fossify.commons.R.string.close, null)
            .apply {
                activity.setupDialogStuff(
                    binding.root,
                    this,
                    titleId = R.string.live_text_title,
                ) { alertDialog ->
                    dialog = alertDialog
                    loadText()
                }
            }
    }

    private fun loadText() {
        ensureBackgroundThread {
            val cached = try {
                activity.imageTextDB.forPath(mediaPath)
            } catch (_: Exception) {
                null
            }
            if (cached != null && cached.text.isNotEmpty()) {
                showText(cached.text)
                return@ensureBackgroundThread
            }
            // Fall back to scanning right now. ML Kit Text Recognition uses
            // a roman-script model — good for Latin, OK for many languages.
            val live = runLiveOcr(mediaPath)
            if (live.isNullOrBlank()) {
                showText("") // → "no text" branch
            } else {
                showText(live)
                persistOcr(mediaPath, live)
            }
        }
    }

    private fun runLiveOcr(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) return null
            val image = InputImage.fromFilePath(activity, Uri.fromFile(file))
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val result = Tasks.await(recognizer.process(image))
                result.text
                    .replace("\r", "")
                    .lineSequence()
                    .map { it.trimEnd() }
                    .joinToString("\n")
                    .trim()
            } finally {
                recognizer.close()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun persistOcr(path: String, text: String) {
        try {
            activity.imageTextDB.insert(
                ImageText(
                    id = null,
                    mediaPath = path,
                    text = text
                        .replace('\n', ' ')
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .lowercase(),
                    indexedAt = System.currentTimeMillis(),
                )
            )
        } catch (_: Exception) {
            // Cache write is best-effort.
        }
    }

    private fun showText(text: String) {
        activity.runOnUiThread {
            if (text.isEmpty()) {
                binding.liveTextStatus.text = activity.getString(R.string.live_text_none_found)
                binding.liveTextStatus.visibility = android.view.View.VISIBLE
                binding.liveTextBody.text = ""
            } else {
                binding.liveTextStatus.visibility = android.view.View.GONE
                binding.liveTextBody.text = text
            }
        }
    }

    private fun copyAll() {
        val text = binding.liveTextBody.text?.toString().orEmpty()
        if (text.isEmpty()) {
            activity.toast(R.string.live_text_none_found)
            return
        }
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("text", text))
        activity.toast(R.string.live_text_copied)
    }
}
