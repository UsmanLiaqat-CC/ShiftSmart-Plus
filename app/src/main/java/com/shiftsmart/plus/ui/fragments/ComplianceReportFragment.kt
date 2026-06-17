package com.shiftsmart.plus.ui.fragments

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shiftsmart.plus.R
import com.shiftsmart.plus.databinding.FragmentComplianceReportBinding
import com.shiftsmart.plus.models.ComplianceReportItem
import com.shiftsmart.plus.ui.activities.ComplaintAlertActivity
import com.shiftsmart.plus.utils.SharedPref
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class ComplianceReportFragment : Fragment() {

    private lateinit var mBinding: FragmentComplianceReportBinding
    private lateinit var adapter: ComplianceReportAdapter

    private var startCalendar: Calendar? = null
    private var endCalendar: Calendar? = null

    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    // Pagination state
    private var currentPage = 1
    private var isLoadingMore = false
    private var hasMorePages = false
    private val allItems = mutableListOf<ComplianceReportItem>()

    private val FIFTEEN_MINUTES_MS = 15 * 60 * 1000L

    companion object {
        const val PAGE_SIZE = 10
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        mBinding = FragmentComplianceReportBinding.inflate(inflater, container, false)
        return mBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Reset badge count when this screen is opened
        SharedPref.getInstance(requireContext())?.resetComplianceBadgeCount()

        setupRecyclerView()
        setupClickListeners()
        setupSwipeRefresh()
    }

    private fun setupRecyclerView() {
        adapter = ComplianceReportAdapter { item -> onItemClicked(item) }
        mBinding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        mBinding.recyclerView.adapter = adapter

        // Endless scroll pagination
        mBinding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (!isLoadingMore && hasMorePages) {
                    val lm = rv.layoutManager as LinearLayoutManager
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = lm.itemCount
                    if (lastVisible >= total - 3) {
                        loadMoreItems()
                    }
                }
            }
        })
    }

    private fun setupClickListeners() {
        mBinding.backBtn.setOnClickListener { findNavController().popBackStack() }

        mBinding.startDateEt.setOnClickListener { showStartDatePicker() }
        mBinding.endDateEt.setOnClickListener { showEndDatePicker() }

        mBinding.loadReportsBtn.setOnClickListener { loadReports(reset = true) }

        mBinding.retryBtn.setOnClickListener { loadReports(reset = true) }
    }

    private fun setupSwipeRefresh() {
        mBinding.swipeRefresh.setOnRefreshListener { loadReports(reset = true) }
    }

    private fun showStartDatePicker() {
        val cal = startCalendar ?: Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                startCalendar = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                mBinding.startDateEt.setText(dateFormat.format(startCalendar!!.time))
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showEndDatePicker() {
        val cal = endCalendar ?: Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                endCalendar = Calendar.getInstance().apply {
                    set(year, month, day, 23, 59, 59)
                    set(Calendar.MILLISECOND, 999)
                }
                mBinding.endDateEt.setText(dateFormat.format(endCalendar!!.time))
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun loadReports(reset: Boolean) {
        if (reset) {
            currentPage = 1
            allItems.clear()
            hasMorePages = false
        }
        showShimmer()
        simulateFetchFromApi(
            startMs = startCalendar?.timeInMillis,
            endMs = endCalendar?.timeInMillis,
            page = currentPage
        )
    }

    private fun loadMoreItems() {
        if (isLoadingMore || !hasMorePages) return
        isLoadingMore = true
        currentPage++
        simulateFetchFromApi(
            startMs = startCalendar?.timeInMillis,
            endMs = endCalendar?.timeInMillis,
            page = currentPage
        )
    }

    /**
     * Stub for the real API call. Replace with actual Retrofit/ViewModel call when API is ready.
     * Currently loads sample data to preview how the list will look.
     */
    private fun simulateFetchFromApi(startMs: Long?, endMs: Long?, page: Int) {
        mBinding.root.postDelayed({
            if (!isAdded) return@postDelayed
            isLoadingMore = false
            mBinding.swipeRefresh.isRefreshing = false
            hideShimmer()

            // TODO: Replace with real API response handling when backend is ready.
            // Load sample data so the UI layout is visible during development.
            val now = System.currentTimeMillis()
            val sampleItems = listOf(
                ComplianceReportItem(
                    id = "1",
                    title = "Location Update Missing",
                    message = "No location update received for over 10 minutes during active shift. Please check-in to confirm your presence.",
                    complianceType = "Location",
                    isAcknowledged = false,
                    createdAt = now - (8 * 60 * 1000L)  // 8 minutes ago — within 15-min window
                ),
                ComplianceReportItem(
                    id = "2",
                    title = "Check-In Compliance",
                    message = "You missed the scheduled check-in at 09:00 AM. Supervisor has been notified. Please provide a reason for the delay.",
                    complianceType = "Attendance",
                    isAcknowledged = true,
                    createdAt = now - (2 * 60 * 60 * 1000L)  // 2 hours ago
                ),
                ComplianceReportItem(
                    id = "3",
                    title = "GPS Signal Lost",
                    message = "GPS signal was lost for an extended period. Ensure location services are enabled and the app is running in the background.",
                    complianceType = "Location",
                    isAcknowledged = false,
                    createdAt = now - (45 * 60 * 1000L)  // 45 minutes ago
                ),
                ComplianceReportItem(
                    id = "4",
                    title = "Shift Start Compliance",
                    message = "Shift started at 08:00 AM but first location ping was received at 08:22 AM. A 22-minute gap was detected.",
                    complianceType = "Shift",
                    isAcknowledged = true,
                    createdAt = now - (24 * 60 * 60 * 1000L)  // yesterday
                ),
                ComplianceReportItem(
                    id = "5",
                    title = "Data Sync Failure",
                    message = "Attendance data could not be synced to the server. Poor network connectivity detected. Data will sync automatically once connected.",
                    complianceType = "Sync",
                    isAcknowledged = false,
                    createdAt = now - (30 * 60 * 1000L)  // 30 minutes ago
                ),
                ComplianceReportItem(
                    id = "6",
                    title = "Overtime Detected",
                    message = "You worked 2 hours beyond your scheduled shift end time. Please confirm with your supervisor if this was authorised overtime.",
                    complianceType = "Attendance",
                    isAcknowledged = true,
                    createdAt = now - (3 * 60 * 60 * 1000L)  // 3 hours ago
                )
            )
            onDataLoaded(sampleItems, hasMore = false)
        }, 1200L)
    }

    // Call this from ViewModel/API callback with actual data
    fun onDataLoaded(newItems: List<ComplianceReportItem>, hasMore: Boolean) {
        hideShimmer()
        mBinding.swipeRefresh.isRefreshing = false
        hasMorePages = hasMore
        allItems.addAll(newItems)
        if (allItems.isEmpty()) {
            onEmpty()
        } else {
            showRecyclerView()
            adapter.submitList(allItems.toList())
        }
    }

    // Call this on API/network error
    fun onError(title: String = getString(R.string.error_loading_reports),
                message: String = getString(R.string.network_error_message),
                showRetry: Boolean = true) {
        hideShimmer()
        mBinding.swipeRefresh.isRefreshing = false
        showErrorView(title, message, showRetry)
    }

    // Call this when response is empty
    fun onEmpty() {
        hideShimmer()
        mBinding.swipeRefresh.isRefreshing = false
        showErrorView(
            getString(R.string.no_reports_found),
            getString(R.string.no_reports_message),
            showRetry = false
        )
    }

    private fun showShimmer() {
        mBinding.idleView.visibility = View.GONE
        mBinding.errorView.visibility = View.GONE
        mBinding.recyclerView.visibility = View.GONE
        mBinding.shimmerLayout.visibility = View.VISIBLE
        mBinding.shimmerLayout.startShimmer()
    }

    private fun hideShimmer() {
        mBinding.shimmerLayout.stopShimmer()
        mBinding.shimmerLayout.visibility = View.GONE
    }

    private fun showRecyclerView() {
        mBinding.idleView.visibility = View.GONE
        mBinding.errorView.visibility = View.GONE
        mBinding.recyclerView.visibility = View.VISIBLE
    }

    private fun showErrorView(title: String, message: String, showRetry: Boolean) {
        mBinding.idleView.visibility = View.GONE
        mBinding.recyclerView.visibility = View.GONE
        mBinding.errorTitleTv.text = title
        mBinding.errorMessageTv.text = message
        mBinding.retryBtn.visibility = if (showRetry) View.VISIBLE else View.GONE
        mBinding.errorView.visibility = View.VISIBLE
    }

    private fun onItemClicked(item: ComplianceReportItem) {
        if (item.isAcknowledged) return  // already acknowledged, do nothing

        val ageMs = System.currentTimeMillis() - item.createdAt
        if (ageMs <= FIFTEEN_MINUTES_MS) {
            // Within 15 minutes — open full-screen ComplaintAlertActivity
            val intent = Intent(requireContext(), ComplaintAlertActivity::class.java)
            startActivity(intent)
        } else {
            // Past 15 minutes — show stylish dialog
            showDeadlinePassedDialog()
        }
    }

    private fun showDeadlinePassedDialog() {
        if (!isAdded || activity?.isFinishing == true) return

        val builder = AlertDialog.Builder(requireContext(), com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
        builder.setTitle("⏰ ${getString(R.string.response_deadline_passed)}")
        builder.setMessage(getString(R.string.response_deadline_passed_message))
        builder.setPositiveButton(getString(R.string.ok)) { dialog, _ -> dialog.dismiss() }
        builder.setCancelable(true)

        val dialog = builder.create()
        dialog.show()

        // Style the button red for urgency
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            requireContext().getColor(android.R.color.holo_red_dark)
        )
    }
}
