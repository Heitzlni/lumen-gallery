package org.fossify.gallery.adapters

import android.view.Menu
import android.view.ViewGroup
import com.bumptech.glide.Glide
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupViewBackground
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.gallery.databinding.ItemVaultAlbumBinding
import org.fossify.gallery.helpers.VaultCrypto
import org.fossify.gallery.models.VaultAlbumSummary

class VaultAlbumAdapter(
    activity: BaseSimpleActivity,
    var albums: ArrayList<VaultAlbumSummary>,
    recyclerView: MyRecyclerView,
    itemClick: (Any) -> Unit,
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick) {

    override fun getActionMenuId() = 0
    override fun prepareActionMode(menu: Menu) {}
    override fun actionItemPressed(id: Int) {}
    override fun getSelectableItemCount() = 0
    override fun getIsItemSelectable(position: Int) = false
    override fun getItemSelectionKey(position: Int): Int? = null
    override fun getItemKeyPosition(key: Int) = -1
    override fun onActionModeCreated() {}
    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return createViewHolder(ItemVaultAlbumBinding.inflate(layoutInflater, parent, false).root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val album = albums[position]
        holder.bindView(album, true, false) { itemView, _ ->
            ItemVaultAlbumBinding.bind(itemView).apply {
                root.setupViewBackground(activity)
                vaultAlbumName.apply {
                    text = album.displayName
                    setTextColor(context.getProperTextColor())
                }
                vaultAlbumCount.apply {
                    text = formatCount(album.itemCount)
                    setTextColor(context.getProperTextColor())
                }

                vaultAlbumThumb.setImageDrawable(null)
                if (album.thumbnailFilename.isNotEmpty()) {
                    val name = album.thumbnailFilename
                    vaultAlbumThumb.tag = name
                    ensureBackgroundThread {
                        val file = VaultCrypto.decryptThumbnailToCache(
                            activity.applicationContext, name
                        ) ?: return@ensureBackgroundThread
                        activity.runOnUiThread {
                            if (vaultAlbumThumb.tag == name && !activity.isDestroyed) {
                                Glide.with(activity)
                                    .load(file)
                                    .centerCrop()
                                    .into(vaultAlbumThumb)
                            }
                        }
                    }
                }
            }
        }
        bindViewHolder(holder)
    }

    private fun formatCount(n: Int): String = if (n == 1) "1 item" else "$n items"

    override fun getItemCount() = albums.size
}
