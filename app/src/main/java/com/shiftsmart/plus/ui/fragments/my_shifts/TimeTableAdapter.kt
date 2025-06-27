package com.shiftsmart.plus.ui.fragments.my_shifts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.shiftsmart.plus.databinding.ItemShiftsBinding
import com.shiftsmart.plus.models.MultipleTimeTable
import java.text.SimpleDateFormat
import java.util.Locale

class TimeTableAdapter(
    private val items: List<MultipleTimeTable>,
    private val onItemClicked: (MultipleTimeTable) -> Unit
) : RecyclerView.Adapter<TimeTableAdapter.TimeTableViewHolder>() {

    inner class TimeTableViewHolder(val binding: ItemShiftsBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TimeTableViewHolder {
        val binding = ItemShiftsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TimeTableViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TimeTableViewHolder, position: Int) {
        val item = items[position]

        val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val outputFormat = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())

        val formattedStartDate = try {
            val date = inputFormat.parse(item.startDate.substring(0, 10))
            outputFormat.format(date!!)
        } catch (e: Exception) {
            item.startDate.substring(0, 10)  // fallback
        }

        val formattedEndDate = try {
            val date = inputFormat.parse(item.endDate.substring(0, 10))
            outputFormat.format(date!!)
        } catch (e: Exception) {
            item.endDate.substring(0, 10)  // fallback
        }

        holder.binding.apply {
            startDateTv.text = formattedStartDate
            endDateTv.text = formattedEndDate
            root.setOnClickListener { onItemClicked(item) }
        }
    }


    override fun getItemCount(): Int = items.size
}
