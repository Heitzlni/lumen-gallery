package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import org.fossify.commons.dialogs.SecurityDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.SHOW_ALL_TABS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.VaultAlbumAdapter
import org.fossify.gallery.databinding.ActivityVaultAlbumsBinding
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.vaultItemDB
import org.fossify.gallery.helpers.VAULT_ALBUM_NAME
import org.fossify.gallery.helpers.VAULT_SHOW_ALL_ITEMS
import org.fossify.gallery.models.VaultAlbumSummary

class VaultAlbumsActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityVaultAlbumsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.vaultAlbumsToolbar.title = getString(R.string.vault)

        setupEdgeToEdge(
            padTopSystem = listOf(binding.vaultAlbumsAppbar),
            padBottomSystem = listOf(binding.vaultAlbumsList)
        )
        setupMaterialScrollListener(binding.vaultAlbumsList, binding.vaultAlbumsAppbar)

        binding.vaultAlbumsToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.show_all_vault_media -> {
                    val intent = Intent(this, VaultActivity::class.java).apply {
                        putExtra(VAULT_SHOW_ALL_ITEMS, true)
                    }
                    startActivity(intent)
                    true
                }

                R.id.change_vault_password -> {
                    changeVaultPassword()
                    true
                }

                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.vaultAlbumsAppbar, NavigationIcon.Arrow)
        reloadAlbums()
    }

    private fun reloadAlbums() {
        ensureBackgroundThread {
            val dao = vaultItemDB
            val albumNames = dao.getDistinctAlbumNames()
            val albums = albumNames.map { name ->
                VaultAlbumSummary(
                    name = name,
                    displayName = if (name.isEmpty()) getString(R.string.vault_default_album) else name,
                    itemCount = dao.countByAlbum(name),
                    thumbnailFilename = dao.newestThumbnailForAlbum(name).orEmpty(),
                )
            }

            runOnUiThread {
                binding.vaultAlbumsPlaceholder.apply {
                    text = getString(R.string.vault_empty)
                    beVisibleIf(albums.isEmpty())
                    setTextColor(getProperTextColor())
                }

                val adapter = VaultAlbumAdapter(
                    activity = this,
                    albums = albums.toCollection(ArrayList()),
                    recyclerView = binding.vaultAlbumsList,
                ) { clicked ->
                    val album = clicked as? VaultAlbumSummary ?: return@VaultAlbumAdapter
                    val intent = Intent(this, VaultActivity::class.java).apply {
                        putExtra(VAULT_ALBUM_NAME, album.name)
                    }
                    startActivity(intent)
                }
                binding.vaultAlbumsList.adapter = adapter
            }
        }
    }

    private fun changeVaultPassword() {
        val currentHash = config.vaultProtectionHash
        if (currentHash.isEmpty()) {
            toast(R.string.vault_no_password_set)
            return
        }
        SecurityDialog(this, currentHash, config.vaultProtectionType) { _, _, oldVerified ->
            if (!oldVerified) return@SecurityDialog
            SecurityDialog(this, "", SHOW_ALL_TABS) { newHash, newType, newSet ->
                if (newSet) {
                    config.vaultProtectionHash = newHash
                    config.vaultProtectionType = newType
                    toast(R.string.vault_password_changed)
                }
            }
        }
    }
}
