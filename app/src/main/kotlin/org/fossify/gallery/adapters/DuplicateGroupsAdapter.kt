package org.fossify.gallery.adapters

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import org.fossify.commons.extensions.formatSize
import org.fossify.gallery.R
import org.fossify.gallery.helpers.DupeFinder

class DuplicateGroupsAdapter(
    private val activity: Activity,
    var groups: MutableList<DupeFinder.Group>,
    private val onDeleteExtras: (DupeFinder.Group) -> Unit,
    private val onOpenPath: (String) -> Unit,
) : RecyclerView.Adapter<DuplicateGroupsAdapter.VH>() {

    class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val thumbs: ViewGroup = root.findViewById(R.id.duplicate_thumbnails)
        val summary: android.widget.TextView = root.findViewById(R.id.duplicate_summary)
        val delete: android.widget.Button = root.findViewById(R.id.duplicate_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_duplicate_group, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val group = groups[position]
        holder.thumbs.removeAllViews()
        val size = holder.root.resources.getDimensionPixelSize(R.dimen.bottom_filters_thumbnail_size)
        val gap = holder.root.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.small_margin)
        for ((idx, path) in group.paths.withIndex()) {
            val iv = ImageView(holder.root.context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(size, size).apply {
                    if (idx > 0) marginStart = gap
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setOnClickListener { onOpenPath(path) }
                if (idx == 0) {
                    // Highlight the "newest" frame the user is keeping.
                    setPadding(6, 6, 6, 6)
                    setBackgroundColor(0x66FFD740.toInt())
                }
            }
            Glide.with(activity)
                .load(path)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .centerCrop()
                .into(iv)
            holder.thumbs.addView(iv)
        }
        holder.summary.text = holder.root.resources.getString(
            R.string.duplicates_group_summary,
            group.paths.size,
            group.reclaimableBytes.formatSize(),
        )
        holder.delete.setOnClickListener {
            onDeleteExtras(group)
        }
    }

    override fun getItemCount(): Int = groups.size
}
