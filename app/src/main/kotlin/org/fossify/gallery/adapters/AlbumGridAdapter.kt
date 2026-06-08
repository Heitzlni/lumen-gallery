package org.fossify.gallery.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import org.fossify.gallery.R
import org.fossify.gallery.interfaces.AlbumSummary

class AlbumGridAdapter(
    private val context: Context,
    var albums: MutableList<AlbumSummary>,
    private val onClick: (AlbumSummary) -> Unit,
    private val onLongClick: (AlbumSummary) -> Unit,
) : RecyclerView.Adapter<AlbumGridAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cover: android.widget.ImageView = view.findViewById(R.id.album_cover)
        val name: android.widget.TextView = view.findViewById(R.id.album_name)
        val subtitle: android.widget.TextView = view.findViewById(R.id.album_subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_album_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = albums[position]
        holder.name.text = a.name
        holder.subtitle.text = context.getString(R.string.albums_photo_count, a.photoCount)
        val coverPath = a.coverPath ?: a.latestItemPath
        if (coverPath != null) {
            Glide.with(context)
                .load(coverPath)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .centerCrop()
                .into(holder.cover)
        } else {
            holder.cover.setImageDrawable(null)
        }
        holder.itemView.setOnClickListener { onClick(a) }
        holder.itemView.setOnLongClickListener {
            onLongClick(a)
            true
        }
    }

    override fun getItemCount(): Int = albums.size
}
