package com.shiftsmart.plus.ui.fragments.my_shifts

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.shiftsmart.plus.R
import com.shiftsmart.plus.databinding.DialogShiftDetailsBinding
import com.shiftsmart.plus.databinding.FragmentMyShiftsBinding
import com.shiftsmart.plus.models.TimeRange
import com.shiftsmart.plus.models.TimeTable
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.utils.SharedPref
import java.text.SimpleDateFormat
import java.util.Locale

class MyShiftsFragment : Fragment() {

    private  val TAG = "MyShiftsFragment"
    private  var _binding: FragmentMyShiftsBinding? = null
    private val mBinding get() = _binding!!

    private var user:UserModel?=null
    private lateinit var adapter: TimeTableAdapter


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentMyShiftsBinding.inflate(layoutInflater, container, false)
        // Inflate the layout for this fragment
        return mBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

         user= SharedPref.getInstance(requireContext())?.getUser()

        setWeekTimings(
            user?.timetable?.range ?: emptyList(),
            mapOf(
                "Monday" to mBinding.mondayTv,
                "Tuesday" to mBinding.tuesdayTv,
                "Wednesday" to mBinding.wednesdayTv,
                "Thursday" to mBinding.thursdayTv,
                "Friday" to mBinding.fridayTv,
                "Saturday" to mBinding.sartudayTv,
                "Sunday" to mBinding.sundayTv,
            )
        )
        setUpListeners()

        setupRecyclerView()
    }

    private fun setupRecyclerView() {
//        / Setup adapter for multiple shifts
        val shiftList = user?.multipleTimeTables ?: emptyList()

        adapter = TimeTableAdapter(shiftList) { selectedShift ->
            showTimeTableDialog(selectedShift.timetable)
        }

        mBinding.shiftRv.adapter = adapter
        mBinding.shiftRv.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun showTimeTableDialog(timetable: TimeTable) {
        val dialogBinding = DialogShiftDetailsBinding.inflate(layoutInflater)

        val dayMap = timetable.range.associateBy { it.day.lowercase() }

        val blueColor = ContextCompat.getColor(requireContext(), R.color.primary_color)
        val redColor = ContextCompat.getColor(requireContext(), R.color.red)

        val timeFormat24 = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeFormat12 = SimpleDateFormat("hh:mm a", Locale.getDefault())

        fun formatTime(time: String?): String {
            return try {
                timeFormat12.format(timeFormat24.parse(time ?: "")!!)
            } catch (e: Exception) {
                ""
            }
        }

        fun setDay(bindingTextView: TextView, day: String) {
            val data = dayMap[day.lowercase()]
            if (data != null && !data.start.isNullOrBlank() && !data.end.isNullOrBlank()) {
                bindingTextView.text = "${formatTime(data.start)} - ${formatTime(data.end)}"
                bindingTextView.setTextColor(blueColor)
            } else {
                bindingTextView.text = "Off"
                bindingTextView.setTextColor(redColor)
            }
        }

        with(dialogBinding) {
            setDay(mondayTv, "Monday")
            setDay(tuesdayTv, "Tuesday")
            setDay(wednesdayTv, "Wednesday")
            setDay(thursdayTv, "Thursday")
            setDay(fridayTv, "Friday")
            setDay(saturdayTv, "Saturday")
            setDay(sundayTv, "Sunday")
        }

        val dialog = AlertDialog.Builder(requireContext(), R.style.TransparentDialog).create()
        dialog.setView(dialogBinding.root)
        dialog.setCanceledOnTouchOutside(true)

        dialogBinding.cancelIv.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
    fun setWeekTimings(
        rangeList: List<TimeRange>,
        textViews: Map<String, TextView>
    ) {
        val blueColor = ContextCompat.getColor(requireContext(), R.color.primary_color)
        val redColor = ContextCompat.getColor(requireContext(), R.color.red)

        val daysOfWeek = listOf(
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
        )

        val inputFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val outputFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

        fun formatTime(time: String?): String {
            return try {
                outputFormat.format(inputFormat.parse(time ?: "")!!)
            } catch (e: Exception) {
                ""
            }
        }

        for (day in daysOfWeek) {
            val timeRange = rangeList.find { it.day.equals(day, ignoreCase = true) }
            val textView = textViews[day]

            if (textView != null && timeRange != null) {
                if (!timeRange.start.isNullOrBlank() && !timeRange.end.isNullOrBlank()) {
                    val formattedStart = formatTime(timeRange.start)
                    val formattedEnd = formatTime(timeRange.end)
                    textView.text = "$formattedStart - $formattedEnd"
                    textView.setTextColor(blueColor)
                } else {
                    textView.text = "Off"
                    textView.setTextColor(redColor)
                }
            }
        }
    }


    private fun setUpListeners() {
        mBinding.backArrow.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding=null
    }

}