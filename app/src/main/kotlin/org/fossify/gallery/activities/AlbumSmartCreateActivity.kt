package org.fossify.gallery.activities

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.AlbumPhotosAdapter
import org.fossify.gallery.databinding.ActivityAlbumSmartCreateBinding
import org.fossify.gallery.extensions.albumDB
import org.fossify.gallery.extensions.imageEmbeddingDB
import org.fossify.gallery.helpers.EmbeddingSearch
import org.fossify.gallery.models.Album
import org.fossify.gallery.models.AlbumItem
import java.io.File

/**
 * "Create album from a CLIP search". User types a descriptive query
 * (e.g. "forest hike") and a name; we run the CLIP search and present
 * the top matches as a multi-select grid. By default every match is
 * pre-selected; tapping any thumbnail toggles it. Confirm creates the
 * album with the currently-selected matches.
 */
class AlbumSmartCreateActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityAlbumSmartCreateBinding::inflate)
    private lateinit var adapter: AlbumPhotosAdapter

    /** Same set as the adapter's selection — kept locally for the create step. */
    private val selectedPaths = LinkedHashSet<String>()
    private var lastResults: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.albumSmartAppbar),
            padBottomSystem = listOf(binding.albumSmartCreate),
        )
        adapter = AlbumPhotosAdapter(
            context = this,
            paths = mutableListOf(),
            onClickInNormalMode = { _ -> }, // unused — always in selection mode
            onSelectionChanged = { sel ->
                selectedPaths.clear()
                selectedPaths.addAll(sel)
                refreshCreateButton()
            },
        )
        // Always-on selection mode: the grid is the picker.
        adapter.selectAll()
        binding.albumSmartGrid.adapter = adapter

        binding.albumSmartSearch.setOnClickListener { runSearch() }
        binding.albumSmartQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else false
        }
        binding.albumSmartCreate.setOnClickListener { createAlbum() }
        refreshCreateButton()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.albumSmartAppbar, NavigationIcon.Arrow)
    }

    private fun refreshCreateButton() {
        val nameOk = binding.albumSmartName.text?.toString()?.trim()?.isNotEmpty() == true
        binding.albumSmartCreate.isEnabled = nameOk && selectedPaths.isNotEmpty()
        binding.albumSmartCreate.text = if (selectedPaths.isEmpty()) {
            getString(R.string.albums_create)
        } else {
            getString(R.string.albums_smart_create_album, selectedPaths.size.toString())
        }
    }

    private fun runSearch() {
        val query = binding.albumSmartQuery.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            toast(R.string.albums_no_query)
            return
        }
        hideKeyboard()
        binding.albumSmartStatus.text = getString(R.string.duplicates_scanning)
        ensureBackgroundThread {
            // DB access has to happen off the main thread or Room aborts.
            val embeddingCount = try {
                applicationContext.imageEmbeddingDB.count()
            } catch (_: Exception) {
                0
            }
            if (embeddingCount == 0) {
                runOnUiThread {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setMessage(R.string.albums_smart_no_clip_dialog)
                        .setPositiveButton(org.fossify.commons.R.string.ok, null)
                        .show()
                    binding.albumSmartStatus.text = ""
                }
                return@ensureBackgroundThread
            }
            val results = EmbeddingSearch.search(applicationContext, query)
                .filter { File(it).exists() }
            runOnUiThread {
                lastResults = results
                adapter.paths = results.toMutableList()
                adapter.selectAll()
                adapter.notifyDataSetChanged()
                binding.albumSmartStatus.text = if (results.isEmpty()) {
                    getString(R.string.albums_no_matches)
                } else {
                    getString(R.string.albums_match_count, results.size)
                }
                if (binding.albumSmartName.text.isNullOrEmpty()) {
                    binding.albumSmartName.setText(query.replaceFirstChar { it.uppercaseChar() })
                }
                refreshCreateButton()
            }
        }
    }

    private fun createAlbum() {
        val name = binding.albumSmartName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty() || selectedPaths.isEmpty()) return
        val pathsToAdd = selectedPaths.toList()
        ensureBackgroundThread {
            val dao = applicationContext.albumDB
            val existing = dao.getByName(name)
            val id = existing?.id ?: dao.insertAlbum(
                Album(id = null, name = name, coverPath = null, createdAt = System.currentTimeMillis())
            )
            if (id <= 0L) {
                runOnUiThread { toast(org.fossify.commons.R.string.unknown_error_occurred) }
                return@ensureBackgroundThread
            }
            val now = System.currentTimeMillis()
            dao.insertItems(pathsToAdd.map {
                AlbumItem(id = null, albumId = id, mediaPath = it, addedAt = now)
            })
            runOnUiThread {
                toast(getString(R.string.albums_create_done, name, pathsToAdd.size))
                finish()
            }
        }
    }
}
