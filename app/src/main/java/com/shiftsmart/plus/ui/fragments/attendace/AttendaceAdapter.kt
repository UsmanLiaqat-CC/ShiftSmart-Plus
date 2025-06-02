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
        holder.bind(getItem(position))
    }

    class AttendanceViewHolder(
        private val binding: ItemAttendaceBinding,
        private val context: Context
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecordData) {
            binding.storeNameTv.text = item.store?.name ?: context.getString(R.string.store_not_found)
            binding.attendaceTypeTv.text = item.attendanceType
            binding.attendaceStatusTv.text = item.attendanceStatus
            binding.attendaceCoordinatesTv.text = "${item.coordinates?.coordinates?.get(1).toString()},${item.coordinates?.coordinates?.get(0).toString()}"

            if (!item.date.isNullOrBlank()) {
                val zonedDateTime = ZonedDateTime.parse(item.date)
                val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())
                val timeFormatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault())

                val formattedDate = zonedDateTime.format(dateFormatter)
                val formattedTime = zonedDateTime.format(timeFormatter)

                binding.dateTv.text = "Date:"+formattedDate
                binding.timeTv.text = "Time:"+formattedTime
            } else {
                binding.dateTv.text = context.getString(R.string.unknown_date)
                binding.timeTv.text = context.getString(R.string.unknown_time)
            }

        }
    }

    class DiffCallback : DiffUtil.ItemCallback<RecordData>() {
        override fun areItemsTheSame(oldItem: RecordData, newItem: RecordData): Boolean =
            oldItem._id == newItem._id

        override fun areContentsTheSame(oldItem: RecordData, newItem: RecordData): Boolean =
            oldItem == newItem
    }
}
