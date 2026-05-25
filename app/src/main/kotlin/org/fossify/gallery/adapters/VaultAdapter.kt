package org.fossify.gallery.adapters

import android.view.Menu
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.Glide
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.extensions.formatDate
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupViewBackground
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ItemVaultBinding
import org.fossify.gallery.databinding.ItemVaultHeaderBinding
import org.fossify.gallery.helpers.VaultCrypto
import org.fossify.gallery.models.VaultItem
import org.fossify.gallery.models.VaultListItem

class VaultAdapter(
    activity: BaseSimpleActivity,
    var rows: ArrayList<VaultListItem>,
    recyclerView: MyRecyclerView,
    val onActionDelete: (ArrayList<VaultItem>) -> Unit,
    val onActionExport: (ArrayList<VaultItem>) -> Unit,
    val onActionRestore: (ArrayList<VaultItem>) -> Unit,
    itemClick: (Any) -> Unit,
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick) {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ENTRY = 1
    }

    init {
        setupDragListener(true)
    }

    override fun getActionMenuId() = R.menu.cab_vault

    override fun prepareActionMode(menu: Menu) {}

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) return

        when (id) {
            R.id.cab_vault_delete -> {
                onActionDelete(getSelectedItems())
                finishActMode()
            }

            R.id.cab_vault_export -> {
                onActionExport(getSelectedItems())
                finishActMode()
            }

            R.id.cab_vault_restore -> {
                onActionRestore(getSelectedItems())
                finishActMode()
            }

            R.id.cab_vault_select_all -> selectAll()
        }
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is VaultListItem.Header -> VIEW_TYPE_HEADER
        is VaultListItem.Entry -> VIEW_TYPE_ENTRY
    }

    override fun getSelectableItemCount() = rows.count { it is VaultListItem.Entry }

    override fun getIsItemSelectable(position: Int) = rows[position] is VaultListItem.Entry

    override fun getItemSelectionKey(position: Int): Int? {
        val row = rows.getOrNull(position) ?: return null
        return if (row is VaultListItem.Entry) row.item.id?.toInt() else null
    }

    override fun getItemKeyPosition(key: Int) = rows.indexOfFirst {
        it is VaultListItem.Entry && it.item.id?.toInt() == key
    }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return if (viewType == VIEW_TYPE_HEADER) {
            createViewHolder(ItemVaultHeaderBinding.inflate(layoutInflater, parent, false).root)
        } else {
            createViewHolder(ItemVaultBinding.inflate(layoutInflater, parent, false).root)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        when (row) {
            is VaultListItem.Header -> {
                val view = holder.itemView
                ItemVaultHeaderBinding.bind(view).apply {
                    vaultHeaderLabel.text = row.title
                    vaultHeaderLabel.setTextColor(activity.getProperTextColor())
                }
                // Headers aren't selectable; don't run bindView's selection wiring.
            }

            is VaultListItem.Entry -> {
                val item = row.item
                holder.bindView(item, true, true) { itemView, _ ->
                    bindEntryRow(itemView, item)
                }
                bindViewHolder(holder)
            }
        }
    }

    private fun bindEntryRow(itemView: View, item: VaultItem) {
        ItemVaultBinding.bind(itemView).apply {
            root.setupViewBackground(activity)
            vaultItemHolder.isSelected =
                item.id?.toInt()?.let { selectedKeys.contains(it) } == true
            vaultItemTitle.apply {
                text = item.originalFilename
                setTextColor(context.getProperTextColor())
            }
            val sizePart = item.originalSizeBytes.formatSize()
            val datePart = item.dateAdded.formatDate(activity)
            vaultItemSubtitle.apply {
                text = "$sizePart · $datePart"
                setTextColor(context.getProperTextColor())
            }

            vaultItemThumb.setImageDrawable(null)
            if (item.thumbnailFilename.isNotEmpty()) {
                val expectedFilename = item.thumbnailFilename
                vaultItemThumb.tag = expectedFilename
                ensureBackgroundThread {
                    val file = VaultCrypto.decryptThumbnailToCache(
                        activity.applicationContext, expectedFilename
                    ) ?: return@ensureBackgroundThread
                    activity.runOnUiThread {
                        if (vaultItemThumb.tag == expectedFilename && !activity.isDestroyed) {
                            Glide.with(activity)
                                .load(file)
                                .centerCrop()
                                .into(vaultItemThumb)
                        }
                    }
                }
            }
        }
    }

    override fun getItemCount() = rows.size

    fun updateRows(newRows: ArrayList<VaultListItem>) {
        rows = newRows
        notifyDataSetChanged()
        finishActMode()
    }

    private fun getSelectedItems(): ArrayList<VaultItem> {
        val out = ArrayList<VaultItem>()
        rows.forEach {
            if (it is VaultListItem.Entry && selectedKeys.contains(it.item.id?.toInt())) {
                out.add(it.item)
            }
        }
        return out
    }
}
