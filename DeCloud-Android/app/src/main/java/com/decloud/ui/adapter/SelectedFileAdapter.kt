package com.decloud.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.decloud.R
import com.decloud.model.FileItem
import com.decloud.model.FileType

/**
 * Adapter for displaying selected files/folders in the ready-to-send screen.
 * Supports both individual files and folder items with subtitle (file count).
 */
class SelectedFileAdapter(
    private val onRemoveClick: (FileItem) -> Unit
) : RecyclerView.Adapter<SelectedFileAdapter.SelectedFileViewHolder>() {

    private val items = mutableListOf<FileItem>()
    private val subtitles = mutableMapOf<Int, String>() // position -> subtitle text
    private var locked = false

    /**
     * Lock/unlock the adapter. When locked, remove buttons are hidden (view-only mode).
     */
    fun setLocked(isLocked: Boolean) {
        if (locked != isLocked) {
            locked = isLocked
            notifyDataSetChanged()
        }
    }

    fun setItems(newItems: List<FileItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /**
     * Set items with subtitles (e.g., folder file counts).
     */
    fun setItemsWithSubtitles(newItems: List<FileItem>, newSubtitles: Map<Int, String>) {
        items.clear()
        items.addAll(newItems)
        subtitles.clear()
        subtitles.putAll(newSubtitles)
        notifyDataSetChanged()
    }

    fun removeItem(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SelectedFileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_selected_file, parent, false)
        return SelectedFileViewHolder(view)
    }

    override fun onBindViewHolder(holder: SelectedFileViewHolder, position: Int) {
        holder.bind(items[position], subtitles[position])
    }

    override fun getItemCount(): Int = items.size

    inner class SelectedFileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.ivIcon)
        private val nameView: TextView = itemView.findViewById(R.id.tvName)
        private val subtitleView: TextView = itemView.findViewById(R.id.tvSubtitle)
        private val sizeView: TextView = itemView.findViewById(R.id.tvSize)
        private val removeButton: ImageButton = itemView.findViewById(R.id.btnRemove)

        fun bind(item: FileItem, subtitle: String?) {
            nameView.text = item.name
            sizeView.text = item.getFormattedSize()

            // Show subtitle if provided (e.g., "12 files" for folders)
            if (subtitle != null) {
                subtitleView.text = subtitle
                subtitleView.visibility = View.VISIBLE
            } else {
                subtitleView.visibility = View.GONE
            }

            val iconRes = when (item.fileType) {
                FileType.FOLDER -> R.drawable.ic_folder
                FileType.IMAGE -> R.drawable.ic_image
                FileType.VIDEO -> R.drawable.ic_video
                FileType.AUDIO -> R.drawable.ic_audio
                FileType.DOCUMENT -> R.drawable.ic_document
            }
            iconView.setImageResource(iconRes)

            // Hide remove button when locked (transfer in progress)
            removeButton.visibility = if (locked) View.GONE else View.VISIBLE
            removeButton.setOnClickListener {
                if (!locked) onRemoveClick(item)
            }
        }
    }
}
