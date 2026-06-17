package com.shiftsmart.plus.ui.fragments

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shiftsmart.plus.databinding.ItemComplianceReportBinding
import com.shiftsmart.plus.models.ComplianceReportItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ComplianceReportAdapter(
    private val onItemClick: (ComplianceReportItem) -> Unit
) : ListAdapter<ComplianceReportItem, ComplianceReportAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ComplianceReportItem>() {
            override fun areItemsTheSame(old: ComplianceReportItem, new: ComplianceReportItem) =
                old.id == new.id
            override fun areContentsTheSame(old: ComplianceReportItem, new: ComplianceReportItem) =
                old == new
        }
        private const val FIFTEEN_MINUTES_MS = 15 * 60 * 1000L
    }

    inner class ViewHolder(val binding: ItemComplianceReportBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemComplianceReportBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        val ctx = b.root.context

        b.titleTv.text = item.title
        b.messageTv.text = item.message
        b.complianceTypeTv.text = item.complianceType

        // Acknowledged status
        if (item.isAcknowledged) {
            b.ackStatusTv.text = ctx.getString(com.shiftsmart.plus.R.string.acknowledged)
            b.ackStatusTv.setBackgroundResource(com.shiftsmart.plus.R.drawable.bg_acknowledged)
        } else {
            b.ackStatusTv.text = ctx.getString(com.shiftsmart.plus.R.string.not_acknowledged)
            b.ackStatusTv.setBackgroundResource(com.shiftsmart.plus.R.drawable.bg_not_acknowledged)
        }

        // Time ago display
        b.timeTv.text = getTimeAgo(item.createdAt)

        // Click handler
        b.root.setOnClickListener { onItemClick(item) }
    }

    private fun getTimeAgo(timestampMs: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestampMs
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
            diff < TimeUnit.HOURS.toMillis(1) -> "${diff / TimeUnit.MINUTES.toMillis(1)}m ago"
            diff < TimeUnit.DAYS.toMillis(1) -> "${diff / TimeUnit.HOURS.toMillis(1)}h ago"
            else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(timestampMs))
        }
    }
}
