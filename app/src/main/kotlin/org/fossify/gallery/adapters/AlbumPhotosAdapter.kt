package org.fossify.gallery.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import org.fossify.gallery.R

/**
 * Photo-grid adapter for an album. Supports single-tap (open viewer) and
 * a multi-select mode toggled by the parent activity. Long-press enters
 * select mode; subsequent taps toggle individual photos.
 */
class AlbumPhotosAdapter(
    private val context: Context,
    var paths: MutableList<String>,
    private val onClickInNormalMode: (String) -> Unit,
    private val onSelectionChanged: (Set<String>) -> Unit,
) : RecyclerView.Adapter<AlbumPhotosAdapter.VH>() {

    private val selected = LinkedHashSet<String>()
    var selectionMode: Boolean = false
        private set

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: android.widget.ImageView = view.findViewById(R.id.album_thumb_image)
        val overlay: View = view.findViewById(R.id.album_thumb_selected_overlay)
        val check: View = view.findViewById(R.id.album_thumb_check)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_album_thumb, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val path = paths[position]
        Glide.with(context)
            .load(path)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .centerCrop()
            .into(holder.image)
        val isSelected = path in selected
        holder.overlay.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.check.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener {
            if (selectionMode) {
                toggleSelection(path)
            } else {
                onClickInNormalMode(path)
            }
        }
        holder.itemView.setOnLongClickListener {
            if (!selectionMode) enterSelectionMode()
            toggleSelection(path)
            true
        }
    }

    override fun getItemCount(): Int = paths.size

    private fun enterSelectionMode() {
        if (selectionMode) return
        selectionMode = true
        notifyDataSetChanged()
    }

    fun exitSelectionMode() {
        if (!selectionMode) return
        selectionMode = false
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(emptySet())
    }

    fun selectAll() {
        if (!selectionMode) enterSelectionMode()
        selected.clear()
        selected.addAll(paths)
        notifyDataSetChanged()
        onSelectionChanged(selected.toSet())
    }

    fun selectedPaths(): Set<String> = selected.toSet()

    private fun toggleSelection(path: String) {
        if (path in selected) selected.remove(path) else selected.add(path)
        notifyDataSetChanged()
        onSelectionChanged(selected.toSet())
    }
}
