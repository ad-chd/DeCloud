package com.decloud.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.decloud.R
import com.decloud.util.TransferLogger

/**
 * Adapter for displaying transfer results in a scrollable list
 * Shows green checkmark for success, red X for failure
 */
class TransferResultAdapter : RecyclerView.Adapter<TransferResultAdapter.ResultViewHolder>() {

    private val results = mutableListOf<TransferLogger.TransferResult>()

    fun setResults(newResults: List<TransferLogger.TransferResult>) {
        results.clear()
        // Sort: failures first, then successes
        results.addAll(newResults.sortedBy { it.success })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transfer_result, parent, false)
        return ResultViewHolder(view)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(results[position])
    }

    override fun getItemCount(): Int = results.size

    inner class ResultViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivStatus: ImageView = itemView.findViewById(R.id.ivStatus)
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvFilePath: TextView = itemView.findViewById(R.id.tvFilePath)

        fun bind(result: TransferLogger.TransferResult) {
            tvFileName.text = result.fileName

            // Show path if available
            if (result.filePath.isNotEmpty() && result.filePath != result.fileName) {
                tvFilePath.visibility = View.VISIBLE
                tvFilePath.text = result.filePath
            } else {
                tvFilePath.visibility = View.GONE
            }

            // Set status icon
            if (result.success) {
                ivStatus.setImageResource(R.drawable.ic_success)
            } else {
                ivStatus.setImageResource(R.drawable.ic_error_circle)
            }
        }
    }
}
