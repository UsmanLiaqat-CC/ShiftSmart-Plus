package com.shiftsmart.plus.ui.fragments

import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shiftsmart.plus.R
import com.shiftsmart.plus.databinding.ItemComplianceReportBinding
import com.shiftsmart.plus.models.ComplianceReportItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        private val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
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
        // Show description below title only when it's non-blank
        if (item.description.isNotBlank()) {
            b.messageTv.text = item.description
            b.messageTv.visibility = android.view.View.VISIBLE
        } else {
            b.messageTv.visibility = android.view.View.GONE
        }

        // Sent date
        b.sentDateTv.text = dateFormat.format(Date(item.createdAt))

        // Ack status badge
        when {
            item.isPendingSync -> {
                b.ackStatusTv.text = "Pending Sync"
                b.ackStatusTv.setBackgroundResource(R.drawable.bg_pending_sync)
                b.responseIconIv.setImageResource(R.drawable.ic_circle_check)
                b.responseIconIv.setColorFilter(0xFF1565C0.toInt(), PorterDuff.Mode.SRC_IN)
                b.responseStatusTv.text = "Acknowledged (not synced)"
                b.responseStatusTv.setTextColor(0xFF1565C0.toInt())
            }
            item.isAcknowledged -> {
                b.ackStatusTv.text = ctx.getString(R.string.acknowledged)
                b.ackStatusTv.setBackgroundResource(R.drawable.bg_acknowledged)
                b.responseIconIv.setImageResource(R.drawable.ic_circle_check)
                b.responseIconIv.setColorFilter(ContextCompat.getColor(ctx, R.color.green), PorterDuff.Mode.SRC_IN)
                b.responseStatusTv.text = if (item.respondedAt != null) {
                    "Responded \u00b7 ${dateFormat.format(Date(item.respondedAt))}"
                } else { "Responded" }
                b.responseStatusTv.setTextColor(ContextCompat.getColor(ctx, R.color.green))
            }
            else -> {
                b.ackStatusTv.text = ctx.getString(R.string.not_acknowledged)
                b.ackStatusTv.setBackgroundResource(R.drawable.bg_not_acknowledged)
                b.responseIconIv.setImageResource(R.drawable.ic_circle_uncheck)
                b.responseIconIv.setColorFilter(0xFFF77128.toInt(), PorterDuff.Mode.SRC_IN)
                b.responseStatusTv.text = "Not Responded"
                b.responseStatusTv.setTextColor(0xFFF77128.toInt())
            }
        }

        b.root.setOnClickListener { onItemClick(item) }
    }
}
