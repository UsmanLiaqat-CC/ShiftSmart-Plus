package com.shiftsmart.plus.ui.fragments.attendace

import android.app.DatePickerDialog
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shiftsmart.plus.R
import com.shiftsmart.plus.databinding.FragmentAttendanceBinding
import com.shiftsmart.plus.utils.Resource
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@AndroidEntryPoint
class AttendanceFragment : Fragment() {

    private val TAG = "AttendanceFragment"
    private lateinit var mBinding: FragmentAttendanceBinding
    private var startDateUtc: String=""

    val mainViewModel: MainViewModel by viewModels()

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar

    private var isLoading = false
    private var isLastPage = false

    private val pageSize = 50
    private var currentPage = 1
    private var totalPages = 1

    private lateinit var attendanceAdapter: AttendanceAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        mBinding = FragmentAttendanceBinding.inflate(inflater, container, false)
        return mBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Handle window insets for edge-to-edge display on newer devices
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.headerLl) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = systemBars.top)
            insets
        }

        recyclerView = mBinding.recordsRv
        progressBar = mBinding.progressBar

        setupRecyclerView()
        setUpClickListener()
        observeViewModel()

        currentPage = 1
        loadAttendanceData()
        // Load initial data if dates are already selected or wait for user input
    }

    private fun observeViewModel() {
        mainViewModel.attendceRecordResponse.observe(viewLifecycleOwner) { resource ->
            when (resource) {
                is Resource.Loading -> {
                    progressBar.visibility = View.VISIBLE
                    mBinding.noRecordFound.visibility = View.GONE  // hide empty message while loading
                }
                is Resource.Success -> {
                    progressBar.visibility = View.GONE
                    isLoading = false

                    val newData = resource.data?.data ?: emptyList()
                    totalPages = resource.data?.meta?.totalPages ?: 1
                    isLastPage = currentPage >= totalPages
                    Log.d("PaginationDebug", "currentPage: $currentPage, totalPages: $totalPages, isLastPage: $isLastPage\n:${resource.data?.meta}")

                    // Show empty message if no records at all on first page
                    if (currentPage == 1 && newData.isEmpty()) {
                        mBinding.noRecordFound.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    } else {
                        mBinding.noRecordFound.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE

                        val currentList = if (currentPage == 1) {
                            mutableListOf()
                        } else {
                            attendanceAdapter.currentList.toMutableList()
                        }

                        currentList.addAll(newData)
                        attendanceAdapter.submitList(currentList.toList()) // trigger DiffUtil
                    }
                }
                is Resource.Error -> {
                    progressBar.visibility = View.GONE
                    isLoading = false
                    Utils.showSnackBar(resource.message ?: "Error", mBinding.root)
                }
            }
        }
    }

    private fun setupRecyclerView() {
        attendanceAdapter = AttendanceAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = attendanceAdapter

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)

                if (dy > 0) { // Scroll down
                    val layoutManager = rv.layoutManager as LinearLayoutManager
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val pastVisibleItems = layoutManager.findFirstVisibleItemPosition()

                    Log.d("RecyclerViewDebug", "Scrolled: dy=$dy")
                    Log.d("RecyclerViewDebug", "visibleItemCount = $visibleItemCount")
                    Log.d("RecyclerViewDebug", "totalItemCount = $totalItemCount")
                    Log.d("RecyclerViewDebug", "pastVisibleItems = $pastVisibleItems")
                    Log.d("RecyclerViewDebug", "isLoading = $isLoading")
                    Log.d("RecyclerViewDebug", "isLastPage = $isLastPage")

                    if (!isLoading && !isLastPage) {
                        if ((visibleItemCount + pastVisibleItems) >= totalItemCount) {
                            Log.d("RecyclerViewDebug", "End reached, loading next page: ${currentPage + 1}")
                            currentPage++
                            loadAttendanceData()
                        }
                    }
                }
            }
        })
    }

    private fun loadAttendanceData() {
        if (isLoading || isLastPage) return

        isLoading = true
        val token = SharedPref.getInstance(requireContext())?.getToken() ?: ""
        val user = SharedPref.getInstance(requireContext())?.getUser()
        user?.let { it ->
            mainViewModel.getRecords(
                startDate = startDateUtc!!,
                token = token,
                userId = it._id,
                page = currentPage,
                limit = pageSize
            )
        }
    }

    private fun setUpClickListener() {
        mBinding.backArrow.setOnClickListener{
            findNavController().navigateUp()
        }
        mBinding.doneBtn.setOnClickListener {
            if (startDateUtc.isNullOrEmpty()) {
                Utils.showSnackBar(getString(R.string.please_select_a_start_date), mBinding.root)
            }  else {
                currentPage = 1 // Reset pagination
                isLoading=false
                isLastPage=false
                loadAttendanceData()
            }
        }

        val dateClickListener = View.OnClickListener {
            showDatePicker { displayDate, utcDate ->
                when (it.id) {
                    R.id.start_iv, R.id.start_tv -> {
                        mBinding.startTv.text = displayDate
                        startDateUtc = utcDate
                    }

                }
            }
        }

        mBinding.startIv.setOnClickListener(dateClickListener)
        mBinding.startTv.setOnClickListener(dateClickListener)

    }

    private fun showDatePicker(onDateSelected: (displayDate: String, utcDate: String) -> Unit) {
        val calendar = Calendar.getInstance()
        val datePicker = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val displayDate = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth)

                val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
                isoFormat.timeZone = TimeZone.getTimeZone("UTC")
                val utcDate = isoFormat.format(utcCalendar.time)

                onDateSelected(displayDate, utcDate)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        datePicker.show()
    }
}
