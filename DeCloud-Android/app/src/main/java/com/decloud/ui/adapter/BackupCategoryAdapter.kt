package com.decloud.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.decloud.R
import com.decloud.util.BackupCategory
import com.decloud.util.CategoryScanResult

data class CategoryDisplayItem(
    val category: BackupCategory,
    var isChecked: Boolean,
    var isScanning: Boolean = false,
    var scanResult: CategoryScanResult? = null
)

class BackupCategoryAdapter(
    private val onCheckChanged: (BackupCategory, Boolean) -> Unit,
    private val onRowClicked: ((BackupCategory) -> Unit)? = null
) : RecyclerView.Adapter<BackupCategoryAdapter.CategoryViewHolder>() {

    var showChevron: Boolean = false

    private val items = mutableListOf<CategoryDisplayItem>()

    fun setItems(categories: List<BackupCategory>) {
        items.clear()
        items.addAll(categories.map { CategoryDisplayItem(it, it.defaultEnabled) })
        notifyDataSetChanged()
    }

    /**
     * Set pre-built display items directly (used by SelectionActivity edit mode)
     */
    fun setDisplayItems(displayItems: List<CategoryDisplayItem>) {
        items.clear()
        items.addAll(displayItems)
        notifyDataSetChanged()
    }

    fun setCategoryScanning(category: BackupCategory) {
        val index = items.indexOfFirst { it.category == category }
        if (index >= 0) {
            items[index].isScanning = true
            notifyItemChanged(index)
        }
    }

    fun setCategoryResult(category: BackupCategory, result: CategoryScanResult) {
        val index = items.indexOfFirst { it.category == category }
        if (index >= 0) {
            items[index].isScanning = false
            items[index].scanResult = result
            notifyItemChanged(index)
        }
    }

    fun getSelectedCategories(): List<BackupCategory> {
        return items.filter { it.isChecked && it.scanResult?.hasPermission != false }
            .map { it.category }
    }

    fun getSelectedTotalFiles(): Int {
        return items.filter { it.isChecked }
            .sumOf { it.scanResult?.fileCount ?: 0 }
    }

    fun getSelectedTotalSize(): Long {
        return items.filter { it.isChecked }
            .sumOf { it.scanResult?.totalSize ?: 0L }
    }

    fun getSelectedExcludedCount(): Int {
        return items.filter { it.isChecked }
            .sumOf { it.scanResult?.excludedCount ?: 0 }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_backup_category, parent, false)
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    private fun getTintColor(category: BackupCategory): Int {
        return when (category) {
            BackupCategory.IMAGES -> R.color.icon_images
            BackupCategory.VIDEOS -> R.color.icon_videos
            BackupCategory.AUDIO -> R.color.icon_audio
            BackupCategory.DOCUMENTS -> R.color.icon_documents
            BackupCategory.DOWNLOADS -> R.color.icon_downloads
            BackupCategory.CONTACTS -> R.color.icon_contacts
            BackupCategory.OTHER -> R.color.icon_documents
        }
    }

    inner class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.ivCategoryIcon)
        private val nameView: TextView = itemView.findViewById(R.id.tvCategoryName)
        private val descriptionView: TextView = itemView.findViewById(R.id.tvCategoryDescription)
        private val resultView: TextView = itemView.findViewById(R.id.tvCategoryResult)
        private val excludedView: TextView = itemView.findViewById(R.id.tvExcluded)
        private val progressScanning: ProgressBar = itemView.findViewById(R.id.progressScanning)
        private val checkbox: CheckBox = itemView.findViewById(R.id.cbCategory)
        private val chevronView: ImageView? = itemView.findViewById(R.id.ivChevron)

        fun bind(item: CategoryDisplayItem) {
            val category = item.category

            iconView.setImageResource(category.iconResId)
            iconView.setColorFilter(
                ContextCompat.getColor(itemView.context, getTintColor(category))
            )

            nameView.text = category.displayName
            descriptionView.text = category.description

            // Scanning state
            if (item.isScanning) {
                progressScanning.visibility = View.VISIBLE
                resultView.text = "Scanning..."
                resultView.setTextColor(ContextCompat.getColor(itemView.context, R.color.primary))
                excludedView.visibility = View.GONE
            } else if (item.scanResult != null) {
                progressScanning.visibility = View.GONE
                val result = item.scanResult!!

                if (!result.hasPermission) {
                    resultView.text = "Permission required"
                    resultView.setTextColor(ContextCompat.getColor(itemView.context, R.color.warning))
                    checkbox.isEnabled = false
                    checkbox.isChecked = false
                } else if (result.errorMessage != null && result.fileCount == 0) {
                    resultView.text = "Error scanning"
                    resultView.setTextColor(ContextCompat.getColor(itemView.context, R.color.error))
                } else {
                    val sizeText = if (result.totalSize > 0) " (${result.getFormattedSize()})" else ""
                    val itemLabel = if (result.fileCount == 1) "item" else "items"
                    resultView.text = "${result.fileCount} $itemLabel$sizeText"
                    resultView.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_secondary))
                }

                if (result.excludedCount > 0 && result.hasPermission) {
                    excludedView.visibility = View.VISIBLE
                    excludedView.text = "${result.excludedCount} excluded ›"
                    excludedView.setOnClickListener {
                        val excludedFiles = result.excludedFiles
                        if (excludedFiles.isNotEmpty()) {
                            val message = buildString {
                                excludedFiles.forEachIndexed { index, (name, reason) ->
                                    append("${index + 1}. $name\n   Reason: $reason\n")
                                    if (index < excludedFiles.size - 1) append("\n")
                                }
                                if (result.excludedCount > excludedFiles.size) {
                                    append("\n... and ${result.excludedCount - excludedFiles.size} more")
                                }
                            }
                            AlertDialog.Builder(itemView.context)
                                .setTitle("${result.excludedCount} Excluded Files")
                                .setMessage(message)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                } else {
                    excludedView.visibility = View.GONE
                    excludedView.setOnClickListener(null)
                }
            } else {
                progressScanning.visibility = View.GONE
                resultView.text = "Waiting..."
                resultView.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_hint))
                excludedView.visibility = View.GONE
            }

            // Chevron
            val hasFiles = (item.scanResult?.fileCount ?: 0) > 0
            chevronView?.visibility = if (showChevron && hasFiles && onRowClicked != null) View.VISIBLE else View.GONE

            // Checkbox
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = item.isChecked
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                item.isChecked = isChecked
                onCheckChanged(category, isChecked)
            }

            itemView.setOnClickListener {
                if (onRowClicked != null) {
                    onRowClicked.invoke(category)
                } else if (checkbox.isEnabled) {
                    checkbox.isChecked = !checkbox.isChecked
                }
            }
        }
    }
}
