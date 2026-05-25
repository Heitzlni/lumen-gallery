package org.fossify.gallery.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.SecurityDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.SHOW_ALL_TABS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.VaultAdapter
import org.fossify.gallery.databinding.ActivityVaultBinding
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.vaultItemDB
import org.fossify.gallery.helpers.VaultCrypto
import org.fossify.gallery.models.VaultItem

class VaultActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityVaultBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.vaultToolbar.title = getString(R.string.vault)

        setupEdgeToEdge(
            padTopSystem = listOf(binding.vaultAppbar),
            padBottomSystem = listOf(binding.vaultList)
        )
        setupMaterialScrollListener(binding.vaultList, binding.vaultAppbar)

        binding.vaultToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.change_vault_password) {
                changeVaultPassword()
                return@setOnMenuItemClickListener true
            }
            false
        }

        reloadItems()
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

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.vaultAppbar, NavigationIcon.Arrow)
    }

    override fun onDestroy() {
        super.onDestroy()
        ensureBackgroundThread {
            VaultCrypto.wipeCacheDecrypted(applicationContext)
            VaultCrypto.wipeCacheThumbnails(applicationContext)
        }
    }

    private fun reloadItems() {
        ensureBackgroundThread {
            val items = ArrayList(vaultItemDB.getAll())
            runOnUiThread {
                binding.vaultPlaceholder.apply {
                    text = getString(R.string.vault_empty)
                    beVisibleIf(items.isEmpty())
                    setTextColor(getProperTextColor())
                }

                val adapter = VaultAdapter(
                    activity = this,
                    items = items,
                    recyclerView = binding.vaultList,
                    onActionDelete = { selected -> confirmAndDelete(selected) },
                    onActionExport = { selected -> exportItems(selected) },
                ) { itemClicked ->
                    val vaultItem = itemClicked as? VaultItem ?: return@VaultAdapter
                    openItem(vaultItem)
                }
                binding.vaultList.adapter = adapter
            }
        }
    }

    private fun openItem(item: VaultItem) {
        ensureBackgroundThread {
            val decrypted = VaultCrypto.decryptToCache(
                applicationContext, item.encryptedFilename, item.originalFilename
            )
            if (decrypted == null) {
                runOnUiThread { toast(R.string.vault_decrypt_failed) }
                return@ensureBackgroundThread
            }
            val uri = try {
                FileProvider.getUriForFile(
                    this@VaultActivity, "$packageName.provider", decrypted
                )
            } catch (_: Exception) {
                runOnUiThread { toast(R.string.vault_decrypt_failed) }
                return@ensureBackgroundThread
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType.ifEmpty { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runOnUiThread {
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    toast(R.string.vault_no_viewer)
                }
            }
        }
    }

    private fun confirmAndDelete(items: ArrayList<VaultItem>) {
        if (items.isEmpty()) return
        val message = resources.getQuantityString(R.plurals.vault_delete_confirm, items.size, items.size)
        ConfirmationDialog(this, message) {
            ensureBackgroundThread {
                items.forEach { item ->
                    VaultCrypto.deleteEncrypted(applicationContext, item.encryptedFilename)
                    if (item.thumbnailFilename.isNotEmpty()) {
                        VaultCrypto.deleteEncrypted(applicationContext, item.thumbnailFilename)
                    }
                    item.id?.let { vaultItemDB.deleteById(it) }
                }
                runOnUiThread { reloadItems() }
            }
        }
    }

    private fun exportItems(items: ArrayList<VaultItem>) {
        if (items.isEmpty()) return
        ensureBackgroundThread {
            val uris = ArrayList<Uri>()
            items.forEach { item ->
                val decrypted = VaultCrypto.decryptToCache(
                    applicationContext, item.encryptedFilename, item.originalFilename
                ) ?: return@forEach
                try {
                    val uri = FileProvider.getUriForFile(
                        this@VaultActivity, "$packageName.provider", decrypted
                    )
                    uris.add(uri)
                } catch (_: Exception) {
                }
            }
            if (uris.isEmpty()) {
                runOnUiThread { toast(R.string.vault_decrypt_failed) }
                return@ensureBackgroundThread
            }

            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runOnUiThread {
                try {
                    startActivity(Intent.createChooser(send, getString(R.string.export_from_vault)))
                } catch (_: Exception) {
                    toast(R.string.vault_no_viewer)
                }
            }
        }
    }
}
