package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.AlbumGridAdapter
import org.fossify.gallery.databinding.ActivityAlbumsBinding
import org.fossify.gallery.extensions.albumDB
import org.fossify.gallery.interfaces.AlbumSummary
import org.fossify.gallery.models.Album

/**
 * Lists the user's virtual albums in a 2-column grid. The FAB opens a
 * chooser between "empty album", "smart search album" (CLIP), or just
 * jumping into an existing one. Long-press an album for rename / delete.
 */
class AlbumsActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityAlbumsBinding::inflate)
    private lateinit var adapter: AlbumGridAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.albumsAppbar),
            padBottomSystem = listOf(binding.albumsGrid),
        )
        adapter = AlbumGridAdapter(
            context = this,
            albums = mutableListOf(),
            onClick = { a ->
                val intent = Intent(this, AlbumDetailActivity::class.java).apply {
                    putExtra(AlbumDetailActivity.EXTRA_ALBUM_ID, a.id)
                }
                startActivity(intent)
            },
            onLongClick = { a -> showAlbumActions(a) },
        )
        binding.albumsGrid.adapter = adapter
        binding.albumsNewFab.setOnClickListener { showCreateChooser() }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.albumsAppbar, NavigationIcon.Arrow)
        refresh()
    }

    private fun refresh() {
        ensureBackgroundThread {
            val list = applicationContext.albumDB.allAlbumSummaries()
            runOnUiThread {
                adapter.albums = list.toMutableList()
                adapter.notifyDataSetChanged()
                binding.albumsEmpty.beVisibleIf(list.isEmpty())
                binding.albumsGrid.beVisibleIf(list.isNotEmpty())
            }
        }
    }

    private fun showCreateChooser() {
        val items = arrayOf(
            getString(R.string.albums_view_create_simple),
            getString(R.string.albums_view_create_smart),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.albums_new)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> promptCreateEmpty()
                    1 -> launchSmartCreate()
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun promptCreateEmpty() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.albums_name_hint)
            setSingleLine(true)
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.albums_create)
            .setView(container)
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@setPositiveButton
                ensureBackgroundThread {
                    applicationContext.albumDB.insertAlbum(
                        Album(id = null, name = name, coverPath = null, createdAt = System.currentTimeMillis())
                    )
                    runOnUiThread { refresh() }
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun launchSmartCreate() {
        startActivity(Intent(this, AlbumSmartCreateActivity::class.java))
    }

    private fun showAlbumActions(album: AlbumSummary) {
        val items = arrayOf(
            getString(R.string.albums_rename),
            getString(R.string.albums_delete),
        )
        AlertDialog.Builder(this)
            .setTitle(album.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> promptRename(album)
                    1 -> confirmDelete(album)
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun promptRename(album: AlbumSummary) {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.albums_name_hint)
            setSingleLine(true)
            setText(album.name)
            setSelection(text?.length ?: 0)
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.albums_rename)
            .setView(container)
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || name == album.name) return@setPositiveButton
                ensureBackgroundThread {
                    val existing = applicationContext.albumDB.getById(album.id) ?: return@ensureBackgroundThread
                    existing.name = name
                    try {
                        applicationContext.albumDB.updateAlbum(existing)
                    } catch (_: Exception) {
                    }
                    runOnUiThread { refresh() }
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(album: AlbumSummary) {
        ConfirmationDialog(
            this,
            getString(R.string.albums_delete_confirm, album.name),
        ) {
            ensureBackgroundThread {
                applicationContext.albumDB.clearAlbumItems(album.id)
                applicationContext.albumDB.deleteAlbumById(album.id)
                runOnUiThread { refresh() }
            }
        }
    }
}
