package org.fossify.gallery.dialogs

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.extensions.albumDB
import org.fossify.gallery.models.Album
import org.fossify.gallery.models.AlbumItem

/**
 * "Add N photos to album" — shows the existing albums plus a "Create new"
 * option at the bottom. Picking an existing album inserts AlbumItem rows
 * for every selected path; picking Create new prompts for a name and
 * creates the album, then adds the same rows.
 *
 * The selection-mode caller passes `paths` and an `onDone` callback that
 * runs on the main thread when the dialog finishes.
 */
class AddToAlbumDialog(
    private val activity: Activity,
    private val paths: List<String>,
    private val onDone: () -> Unit,
) {

    init {
        ensureBackgroundThread {
            val summaries = activity.applicationContext.albumDB.allAlbumSummaries()
            activity.runOnUiThread { showPicker(summaries) }
        }
    }

    private fun showPicker(summaries: List<org.fossify.gallery.interfaces.AlbumSummary>) {
        val items = mutableListOf<String>().apply {
            summaries.forEach {
                add(activity.getString(R.string.albums_picker_entry, it.name, it.photoCount))
            }
            add(activity.getString(R.string.albums_new))
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.albums_pick_target)
            .setItems(items.toTypedArray()) { _, which ->
                if (which == summaries.size) {
                    promptCreateAndAdd()
                } else {
                    addPathsToAlbum(summaries[which].id, summaries[which].name)
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel) { _, _ -> onDone() }
            .show()
    }

    private fun promptCreateAndAdd() {
        val input = com.google.android.material.textfield.TextInputEditText(activity).apply {
            hint = activity.getString(R.string.albums_name_hint)
            setSingleLine(true)
        }
        val container = android.widget.FrameLayout(activity).apply {
            val pad = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.albums_create)
            .setView(container)
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    activity.toast(R.string.albums_name_hint)
                    onDone()
                } else {
                    createAlbumAndAdd(name)
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel) { _, _ -> onDone() }
            .show()
    }

    private fun addPathsToAlbum(albumId: Long, albumName: String) {
        ensureBackgroundThread {
            val now = System.currentTimeMillis()
            val items = paths.distinct().map {
                AlbumItem(id = null, albumId = albumId, mediaPath = it, addedAt = now)
            }
            activity.applicationContext.albumDB.insertItems(items)
            activity.runOnUiThread {
                activity.toast(activity.getString(R.string.albums_add_done, items.size, albumName))
                onDone()
            }
        }
    }

    private fun createAlbumAndAdd(name: String) {
        ensureBackgroundThread {
            val dao = activity.applicationContext.albumDB
            val existing = dao.getByName(name)
            val id = existing?.id ?: dao.insertAlbum(
                Album(id = null, name = name, coverPath = null, createdAt = System.currentTimeMillis())
            )
            if (id <= 0L) {
                activity.runOnUiThread { onDone() }
                return@ensureBackgroundThread
            }
            val now = System.currentTimeMillis()
            val items = paths.distinct().map {
                AlbumItem(id = null, albumId = id, mediaPath = it, addedAt = now)
            }
            dao.insertItems(items)
            activity.runOnUiThread {
                activity.toast(activity.getString(R.string.albums_create_done, name, items.size))
                onDone()
            }
        }
    }
}
