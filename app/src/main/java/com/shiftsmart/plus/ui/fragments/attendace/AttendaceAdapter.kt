package com.shiftsmart.plus.ui.fragments.attendace

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shiftsmart.plus.R
import com.shiftsmart.plus.databinding.ItemAttendaceBinding
import com.shiftsmart.plus.models.RecordData
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.util.Locale

/**
 * Created by Usman Liaqat on 19,May,2025
 * usmanliaqat@codecoytechnologies.com,
 * CodeCoy Technologies,
 * Lahore, Pakistan.
 */
class AttendanceAdapter : ListAdapter<RecordData, AttendanceAdapter.AttendanceViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AttendanceViewHolder {
        val binding = ItemAttendaceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AttendanceViewHolder(binding, parent.context)
    }

    override fun onBindViewHolder(holder: AttendanceViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    class AttendanceViewHolder(
        private val binding: ItemAttendaceBinding,
        private val context: Context
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecordData, position: Int) {
            // Set row number
            binding.numberTv.text = (position + 1).toString()

            // Set status
            binding.attendaceStatusTv.text = item.attendanceStatus

            // Set background color based on status
            when (item.attendanceStatus?.lowercase()) {
                "online" -> {
                    binding.root.setBackgroundColor(context.getColor(R.color.light_green))
                }
                "offline" -> {
                    binding.root.setBackgroundColor(context.getColor(R.color.light_red))
                }
                else -> {
                    binding.root.setBackgroundColor(context.getColor(android.R.color.white))
                }
            }

            // Set type - show "auto" if type is "default"
            binding.attendaceTypeTv.text = if (item.attendanceType.equals("default", ignoreCase = true)) {
                "auto"
            } else {
                item.attendanceType
            }

            // Set In Store (Yes/No based on if store exists)
            binding.storeNameTv.text = if (item.store != null) "Yes" else "No"

            // Convert UTC date to local time and format
            if (!item.date.isNullOrBlank()) {
                try {
                    // Parse the UTC time from the server
                    val utcDateTime = ZonedDateTime.parse(item.date)

                    // ✅ Format directly as UTC, without converting to local timezone
                    val dateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy, hh:mm:ss a", Locale.getDefault())
                    val formattedUtcDateTime = utcDateTime.format(dateTimeFormatter)
                    binding.dateTv.text = formattedUtcDateTime

                    binding.timeTv.text = item.localTime
                } catch (e: Exception) {
                    binding.dateTv.text = "Unknown"
                    binding.timeTv.text = "--:--"
                }
            } else {
                binding.dateTv.text = "Unknown"
                binding.timeTv.text = "--:--"
            }

            // Store coordinates in hidden field (for compatibility)
            binding.attendaceCoordinatesTv.text = "${item.coordinates?.coordinates?.get(1).toString()},${item.coordinates?.coordinates?.get(0).toString()}"
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<RecordData>() {
        override fun areItemsTheSame(oldItem: RecordData, newItem: RecordData): Boolean =
            oldItem._id == newItem._id

        override fun areContentsTheSame(oldItem: RecordData, newItem: RecordData): Boolean =
            oldItem == newItem
    }
}
