package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.toFileDirItem
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.DuplicateGroupsAdapter
import org.fossify.gallery.databinding.ActivityDuplicatesBinding
import org.fossify.gallery.extensions.imageHashDB
import org.fossify.gallery.extensions.tryDeleteFileDirItem
import org.fossify.gallery.helpers.DupeFinder
import org.fossify.gallery.helpers.HashIndexer
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.SKIP_AUTHENTICATION
import java.io.File

/**
 * Lists groups of near-duplicate photos, each keeping the newest frame
 * highlighted in amber. Tap a thumbnail to open it; tap "Delete extras"
 * to recycle every copy except the newest.
 *
 * The scan kicks off automatically: if hashes aren't already indexed
 * we run [HashIndexer] first, then [DupeFinder]. Subsequent visits
 * skip the index step and just re-run the grouping.
 */
class DuplicatesActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityDuplicatesBinding::inflate)
    private lateinit var adapter: DuplicateGroupsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.duplicatesAppbar),
            padBottomSystem = listOf(binding.duplicatesList),
        )

        adapter = DuplicateGroupsAdapter(
            activity = this,
            groups = mutableListOf(),
            onDeleteExtras = { group -> confirmDeleteGroup(group) },
            onOpenPath = { path -> openInViewer(path) },
        )
        binding.duplicatesList.adapter = adapter

        scan()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.duplicatesAppbar, NavigationIcon.Arrow)
    }

    private fun scan() {
        binding.duplicatesProgress.beVisible()
        binding.duplicatesPlaceholder.beGone()
        binding.duplicatesSummary.text = getString(R.string.duplicates_scanning)
        ensureBackgroundThread {
            // Build the hash table first if it isn't built yet.
            HashIndexer.indexAll(
                applicationContext,
                onProgress = { _, _ -> },
                onDone = { _, _ ->
                    val groups = DupeFinder.findGroups(applicationContext)
                    runOnUiThread { presentGroups(groups) }
                },
            )
        }
    }

    private fun presentGroups(groups: List<DupeFinder.Group>) {
        binding.duplicatesProgress.beGone()
        adapter.groups = groups.toMutableList()
        adapter.notifyDataSetChanged()

        val totalBytes = groups.sumOf { it.reclaimableBytes }
        binding.duplicatesSummary.text = if (groups.isEmpty()) {
            getString(R.string.duplicates_summary_none)
        } else {
            getString(
                R.string.duplicates_summary_found,
                groups.size,
                totalBytes.formatSize(),
            )
        }
        binding.duplicatesPlaceholder.beVisibleIf(groups.isEmpty())
        binding.duplicatesList.beVisibleIf(groups.isNotEmpty())
    }

    private fun confirmDeleteGroup(group: DupeFinder.Group) {
        val extras = group.paths.drop(1)
        if (extras.isEmpty()) return
        ConfirmationDialog(
            this,
            getString(
                R.string.duplicates_delete_confirm,
                extras.size,
                group.reclaimableBytes.formatSize(),
            ),
        ) {
            deleteFiles(extras) {
                // Refresh after delete — drop the group from the list.
                val idx = adapter.groups.indexOf(group)
                if (idx >= 0) {
                    adapter.groups.removeAt(idx)
                    adapter.notifyItemRemoved(idx)
                }
                val remaining = adapter.groups.sumOf { it.reclaimableBytes }
                binding.duplicatesSummary.text = if (adapter.groups.isEmpty()) {
                    getString(R.string.duplicates_summary_none)
                } else {
                    getString(
                        R.string.duplicates_summary_found,
                        adapter.groups.size,
                        remaining.formatSize(),
                    )
                }
                binding.duplicatesPlaceholder.beVisibleIf(adapter.groups.isEmpty())
            }
        }
    }

    private fun deleteFiles(paths: List<String>, onDone: () -> Unit) {
        ensureBackgroundThread {
            var remaining = paths.size
            if (remaining == 0) {
                runOnUiThread(onDone)
                return@ensureBackgroundThread
            }
            for (p in paths) {
                val file = File(p)
                if (!file.exists()) {
                    if (--remaining == 0) runOnUiThread(onDone)
                    continue
                }
                tryDeleteFileDirItem(
                    file.toFileDirItem(applicationContext),
                    allowDeleteFolder = false,
                    deleteFromDatabase = true,
                ) {
                    applicationContext.imageHashDB.deleteByPath(p)
                    if (--remaining == 0) runOnUiThread(onDone)
                }
            }
        }
    }

    private fun openInViewer(path: String) {
        Intent(this, ViewPagerActivity::class.java).apply {
            putExtra(PATH, path)
            putExtra(SKIP_AUTHENTICATION, true)
            startActivity(this)
        }
    }
}
