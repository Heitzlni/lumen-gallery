package org.fossify.gallery.activities

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import org.fossify.gallery.helpers.VAULT_ALBUM_NAME
import org.fossify.gallery.helpers.VAULT_SHOW_ALL_ITEMS
import org.fossify.gallery.helpers.VaultCrypto
import org.fossify.gallery.models.VaultItem
import org.fossify.gallery.models.VaultListItem
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class VaultActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityVaultBinding::inflate)

    private var filterAlbumName: String? = null
    private var showAllItems: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        showAllItems = intent.getBooleanExtra(VAULT_SHOW_ALL_ITEMS, false)
        filterAlbumName = if (showAllItems) null else intent.getStringExtra(VAULT_ALBUM_NAME)

        binding.vaultToolbar.title = when {
            showAllItems -> getString(R.string.vault_show_all_media)
            filterAlbumName == null -> getString(R.string.vault)
            filterAlbumName!!.isEmpty() -> getString(R.string.vault_default_album)
            else -> filterAlbumName
        }

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
            val items = when {
                showAllItems -> vaultItemDB.getAll()
                filterAlbumName != null -> vaultItemDB.getByAlbum(filterAlbumName!!)
                else -> vaultItemDB.getAll()
            }
            val rows = buildGroupedRows(items)
            runOnUiThread {
                binding.vaultPlaceholder.apply {
                    text = getString(R.string.vault_empty)
                    beVisibleIf(items.isEmpty())
                    setTextColor(getProperTextColor())
                }

                val adapter = VaultAdapter(
                    activity = this,
                    rows = rows,
                    recyclerView = binding.vaultList,
                    onActionDelete = { selected -> confirmAndDelete(selected) },
                    onActionExport = { selected -> exportItems(selected) },
                    onActionRestore = { selected -> confirmAndRestore(selected) },
                ) { itemClicked ->
                    val vaultItem = itemClicked as? VaultItem ?: return@VaultAdapter
                    openItem(vaultItem)
                }
                binding.vaultList.adapter = adapter
            }
        }
    }

    private fun buildGroupedRows(items: List<VaultItem>): ArrayList<VaultListItem> {
        val rows = ArrayList<VaultListItem>(items.size + 8)
        if (items.isEmpty()) return rows

        val formatter = SimpleDateFormat("LLLL yyyy", Locale.getDefault())
        var lastBucket: String? = null
        val cal = Calendar.getInstance()
        items.forEach { item ->
            // Group by the date the photo was actually taken; if we don't
            // have that (item added pre-v3.1), fall back to date_added.
            val effectiveTimestamp = if (item.dateTaken > 0L) item.dateTaken else item.dateAdded
            cal.time = Date(effectiveTimestamp)
            val bucketKey = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
            if (bucketKey != lastBucket) {
                lastBucket = bucketKey
                rows.add(VaultListItem.Header(formatter.format(cal.time).replaceFirstChar { it.uppercase() }))
            }
            rows.add(VaultListItem.Entry(item))
        }
        return rows
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

    private fun confirmAndRestore(items: ArrayList<VaultItem>) {
        if (items.isEmpty()) return
        val message = resources.getQuantityString(
            R.plurals.vault_restore_confirm, items.size, items.size
        )
        ConfirmationDialog(this, message) {
            ensureBackgroundThread {
                var restored = 0
                items.forEach { item ->
                    if (restoreOne(item)) {
                        VaultCrypto.deleteEncrypted(applicationContext, item.encryptedFilename)
                        if (item.thumbnailFilename.isNotEmpty()) {
                            VaultCrypto.deleteEncrypted(applicationContext, item.thumbnailFilename)
                        }
                        item.id?.let { vaultItemDB.deleteById(it) }
                        restored++
                    }
                }
                runOnUiThread {
                    val msg = if (restored == 0) getString(R.string.vault_restore_failed)
                    else getString(R.string.vault_restore_done, restored)
                    toast(msg)
                    reloadItems()
                }
            }
        }
    }

    private fun restoreOne(item: VaultItem): Boolean {
        val decrypted = VaultCrypto.decryptToCache(
            applicationContext, item.encryptedFilename, item.originalFilename
        ) ?: return false

        val mime = item.mimeType.ifEmpty { "image/*" }
        val isVideo = mime.startsWith("video/")
        // Try to restore to the photo's original folder. If that folder
        // lived outside primary external storage (e.g. SD card) or we
        // don't have a remembered path (older vault items), fall into
        // the default Pictures/FossifyGallery bucket.
        val relativePath = resolveRelativePath(item.originalFolderPath)
            ?: if (isVideo) "Movies/FossifyGallery" else "Pictures/FossifyGallery"
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        // Pick the best timestamp we still have. EXIF/MediaStore "taken" is
        // the right answer for items vaulted with v3.2+; for older items
        // (where dateTaken=0), fall back to the time we recorded when the
        // item was vaulted — still much better than letting MediaStore use
        // the freshly-written file's "now".
        val effectiveDate = if (item.dateTaken > 0L) item.dateTaken else item.dateAdded

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.originalFilename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            if (effectiveDate > 0L) {
                // DATE_TAKEN is in milliseconds, DATE_MODIFIED/ADDED in seconds.
                // Setting all three so gallery apps that prefer one over another
                // still place the photo at its real date.
                put(MediaStore.MediaColumns.DATE_TAKEN, effectiveDate)
                put(MediaStore.MediaColumns.DATE_MODIFIED, effectiveDate / 1000L)
                put(MediaStore.MediaColumns.DATE_ADDED, effectiveDate / 1000L)
            }
        }

        val resolver = contentResolver
        val uri = try {
            resolver.insert(collection, values)
        } catch (_: Exception) {
            null
        } ?: return false

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(decrypted).use { input -> input.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val finalValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, finalValues, null, null)
            }
            // Force the file's filesystem mtime to the original date — some
            // gallery apps display the file's last-modified time rather than
            // MediaStore's DATE_TAKEN.
            if (effectiveDate > 0L) {
                try {
                    val proj = arrayOf(MediaStore.MediaColumns.DATA)
                    resolver.query(uri, proj, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val path = c.getString(0)
                            if (!path.isNullOrEmpty()) {
                                java.io.File(path).setLastModified(effectiveDate)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // best-effort
                }
            }
            true
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            false
        }
    }

    private fun resolveRelativePath(absoluteFolderPath: String): String? {
        if (absoluteFolderPath.isEmpty()) return null
        val external = Environment.getExternalStorageDirectory().absolutePath
        // Trim a trailing slash off both so the equals check works at the root.
        val cleanedSource = absoluteFolderPath.trimEnd('/')
        val cleanedExternal = external.trimEnd('/')
        return when {
            cleanedSource == cleanedExternal -> ""
            cleanedSource.startsWith("$cleanedExternal/") ->
                cleanedSource.substring(cleanedExternal.length + 1)
            else -> null // SD card / OTG / unrecognized — caller falls back
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
