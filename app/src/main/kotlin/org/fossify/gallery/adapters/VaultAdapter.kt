package org.fossify.gallery.adapters

import android.view.Menu
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
import org.fossify.gallery.helpers.VaultCrypto
import org.fossify.gallery.models.VaultItem

class VaultAdapter(
    activity: BaseSimpleActivity,
    var items: ArrayList<VaultItem>,
    recyclerView: MyRecyclerView,
    val onActionDelete: (ArrayList<VaultItem>) -> Unit,
    val onActionExport: (ArrayList<VaultItem>) -> Unit,
    itemClick: (Any) -> Unit,
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick) {

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

            R.id.cab_vault_select_all -> selectAll()
        }
    }

    override fun getSelectableItemCount() = items.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = items.getOrNull(position)?.id?.toInt()

    override fun getItemKeyPosition(key: Int) = items.indexOfFirst { it.id?.toInt() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return createViewHolder(ItemVaultBinding.inflate(layoutInflater, parent, false).root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bindView(item, true, true) { itemView, _ ->
            ItemVaultBinding.bind(itemView).apply {
                root.setupViewBackground(activity)
                vaultItemHolder.isSelected = item.id?.toInt()?.let { selectedKeys.contains(it) } == true
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
                            // Guard against view recycling — only set if the
                            // holder is still bound to this item.
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
        bindViewHolder(holder)
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: ArrayList<VaultItem>) {
        items = newItems
        notifyDataSetChanged()
        finishActMode()
    }

    private fun getSelectedItems(): ArrayList<VaultItem> {
        return items.filter { selectedKeys.contains(it.id?.toInt()) } as ArrayList<VaultItem>
    }
}
