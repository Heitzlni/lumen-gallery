package org.fossify.gallery.activities

import android.os.Bundle
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.AlbumPhotosAdapter
import org.fossify.gallery.databinding.ActivityAlbumDetailBinding
import org.fossify.gallery.extensions.albumDB
import java.io.File

/**
 * Shows the photos inside one virtual album. Tap a thumb to open it
 * full-screen via a quick image dialog. Long-press enters selection mode;
 * the toolbar then exposes "Select all" and "Remove from album".
 */
class AlbumDetailActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityAlbumDetailBinding::inflate)
    private lateinit var adapter: AlbumPhotosAdapter
    private var albumId: Long = -1L
    private var albumName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.albumDetailAppbar),
            padBottomSystem = listOf(binding.albumDetailGrid),
        )
        albumId = intent.getLongExtra(EXTRA_ALBUM_ID, -1L)
        if (albumId <= 0L) {
            finish()
            return
        }

        adapter = AlbumPhotosAdapter(
            context = this,
            paths = mutableListOf(),
            onClickInNormalMode = { path -> openInViewer(path) },
            onSelectionChanged = { sel -> updateToolbarForSelection(sel.size) },
        )
        binding.albumDetailGrid.adapter = adapter
        binding.albumDetailToolbar.inflateMenu(R.menu.menu_album_detail)
        binding.albumDetailToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.album_detail_select_all -> { adapter.selectAll(); true }
                R.id.album_detail_remove -> { confirmRemove(adapter.selectedPaths()); true }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.albumDetailAppbar, NavigationIcon.Arrow)
        refresh()
    }

    override fun onBackPressed() {
        if (adapter.selectionMode) {
            adapter.exitSelectionMode()
            return
        }
        super.onBackPressed()
    }

    private fun refresh() {
        ensureBackgroundThread {
            val album = applicationContext.albumDB.getById(albumId)
            if (album == null) {
                runOnUiThread { finish() }
                return@ensureBackgroundThread
            }
            albumName = album.name
            val paths = applicationContext.albumDB.pathsForAlbum(albumId)
                .filter { File(it).exists() }
            runOnUiThread {
                binding.albumDetailToolbar.title = album.name
                adapter.paths = paths.toMutableList()
                adapter.notifyDataSetChanged()
                binding.albumDetailEmpty.text = getString(R.string.albums_empty)
                binding.albumDetailEmpty.beVisibleIf(paths.isEmpty())
                binding.albumDetailGrid.beVisibleIf(paths.isNotEmpty())
                updateToolbarForSelection(adapter.selectedPaths().size)
            }
        }
    }

    private fun updateToolbarForSelection(count: Int) {
        val menu = binding.albumDetailToolbar.menu
        val inSelection = adapter.selectionMode
        menu.findItem(R.id.album_detail_select_all)?.isVisible = inSelection
        menu.findItem(R.id.album_detail_remove)?.isVisible = inSelection && count > 0
        binding.albumDetailToolbar.title = if (inSelection && count > 0) {
            "$count"
        } else {
            albumName
        }
    }

    private fun confirmRemove(toRemove: Set<String>) {
        if (toRemove.isEmpty()) return
        ConfirmationDialog(
            this,
            getString(R.string.albums_remove_from_confirm, toRemove.size),
        ) {
            ensureBackgroundThread {
                applicationContext.albumDB.removePathsFromAlbum(albumId, toRemove.toList())
                runOnUiThread {
                    adapter.exitSelectionMode()
                    refresh()
                }
            }
        }
    }

    /**
     * Open the tapped photo full-screen in a quick Glide dialog. Routing
     * through ViewPagerActivity here was opening the wrong file because
     * VPA reuses MediaActivity.mMedia as its media list, and album items
     * usually live outside whatever album was last loaded. A simple
     * dismiss-on-tap dialog avoids that whole tangle for now; a proper
     * swipe-through-album viewer is a future polish step.
     */
    private fun openInViewer(path: String) {
        val dialog = android.app.Dialog(
            this, android.R.style.Theme_Black_NoTitleBar_Fullscreen
        )
        val iv = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
            setOnClickListener { dialog.dismiss() }
        }
        com.bumptech.glide.Glide.with(this)
            .load(path)
            .into(iv)
        dialog.setContentView(iv)
        dialog.show()
    }

    companion object {
        const val EXTRA_ALBUM_ID = "album_id"
    }
}
