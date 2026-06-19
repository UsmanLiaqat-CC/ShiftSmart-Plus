package com.shiftsmart.plus.ui.fragments

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shiftsmart.plus.R
import com.shiftsmart.plus.databinding.FragmentComplianceReportBinding
import com.shiftsmart.plus.models.ComplianceReportItem
import com.shiftsmart.plus.models.FieldWorkerComplianceItem
import com.shiftsmart.plus.ui.activities.ComplaintAlertActivity
import com.shiftsmart.plus.utils.Resource
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import androidx.lifecycle.lifecycleScope
import com.shiftsmart.plus.database.DBDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class ComplianceReportFragment : Fragment() {

    private val TAG = "ComplianceReportFragment"
    private lateinit var mBinding: FragmentComplianceReportBinding
    private lateinit var adapter: ComplianceReportAdapter
    private val mainViewModel: MainViewModel by viewModels()

    private var startCalendar: Calendar? = null
    private var endCalendar: Calendar? = null

    private val displayDateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    // Used only for FORMATTING dates to send to the API endpoint
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    /**
     * Parses any ISO-8601 timestamp string (with/without ms, with/without tz offset)
     * using java.time.Instant which handles all standard variants.
     * Returns null if unparseable — callers should treat null as "very old" (epoch 0).
     */
    private fun parseIso(ts: String): Long? = try {
        Instant.parse(ts).toEpochMilli()
    } catch (_: Exception) { null }

    // Pagination state
    private var currentPage = 1
    private var isLoadingMore = false
    private var hasMorePages = false
    private val allItems = mutableListOf<ComplianceReportItem>()

    private val FIFTEEN_MINUTES_MS = 15 * 60 * 1000L

    @Inject lateinit var dao: DBDao

    companion object {
        const val PAGE_SIZE = 10
    }

    override fun onResume() {
        super.onResume()
        if (allItems.isNotEmpty()) {
            // Re-fetch the list so the server-acknowledged status updates are reflected.
            loadReports(reset = true)
            // checkAndMarkPendingSync is called inside the observer after data loads.
        }
    }

    /** Checks DB for locally acknowledged compliance records and marks them as Pending Sync in the list. */
    private fun checkAndMarkPendingSync() {
        val userId = SharedPref.getInstance(requireContext())?.getUser()?._id ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val acknowledgedIds = dao.getAcknowledgedComplianceIds(userId).toSet()
            if (acknowledgedIds.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                val updated = allItems.map { item ->
                    if (!item.isAcknowledged && acknowledgedIds.contains(item.id))
                        item.copy(isPendingSync = true)
                    else item
                }
                allItems.clear()
                allItems.addAll(updated)
                adapter.submitList(allItems.toList())
            }
        }
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

        setDefaultDateRange()
        setupRecyclerView()
        setupClickListeners()
        setupSwipeRefresh()
        observeViewModel()

        // Auto-load on first open
        loadReports(reset = true)
    }

    /** Sets start = 7 days ago, end = today, and fills the EditTexts. All in UTC. */
    private fun setDefaultDateRange() {
        val utc = TimeZone.getTimeZone("UTC")
        val end = Calendar.getInstance(utc).apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val start = (end.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -7)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        startCalendar = start
        endCalendar = end
        mBinding.startDateEt.setText(displayDateFormat.format(start.time))
        mBinding.endDateEt.setText(displayDateFormat.format(end.time))
        Log.d(TAG, "Default date range: ${isoFormat.format(start.time)} → ${isoFormat.format(end.time)}")
    }

    private fun setupRecyclerView() {
        adapter = ComplianceReportAdapter { item -> onItemClicked(item) }
        mBinding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        mBinding.recyclerView.adapter = adapter

        mBinding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (!isLoadingMore && hasMorePages) {
                    val lm = rv.layoutManager as LinearLayoutManager
                    val lastVisible = lm.findLastVisibleItemPosition()
                    if (lastVisible >= lm.itemCount - 3) {
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

    private fun observeViewModel() {
        mainViewModel.complianceResponse.observe(viewLifecycleOwner) { resource ->
            isLoadingMore = false
            mBinding.swipeRefresh.isRefreshing = false
            when (resource) {
                is Resource.Loading -> {
                    if (currentPage == 1) showShimmer()
                }
                is Resource.Success -> {
                    hideShimmer()
                    val body = resource.data ?: return@observe
                    val meta = body.meta
                    hasMorePages = meta.currentPage < meta.totalPages
                    Log.d(TAG, "observeViewModel SUCCESS: total=${meta.total}, items=${body.data.size}")

                    val newItems = try {
                        body.data.map { it.toComplianceReportItem() }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to map compliance items: ${e.message}", e)
                        emptyList()
                    }
                    if (currentPage == 1) allItems.clear()
                    allItems.addAll(newItems)

                    if (allItems.isEmpty()) {
                        onEmpty()
                    } else {
                        showRecyclerView()
                        adapter.submitList(allItems.toList())
                        // Check DB immediately after list renders so pending-sync
                        // items are marked even on the very first load.
                        checkAndMarkPendingSync()
                    }
                    Log.d(TAG, "Loaded page $currentPage / ${meta.totalPages}, total=${meta.total}")
                }
                is Resource.Error -> {
                    hideShimmer()
                    onError(showRetry = true)
                    Log.e(TAG, "Compliance API error: ${resource.message}")
                }
                else -> {}
            }
        }
    }

    private fun FieldWorkerComplianceItem.toComplianceReportItem(): ComplianceReportItem {
        // If sentAt cannot be parsed, treat as epoch-0 so the item is always "too old" to acknowledge.
        val sentAtMs = parseIso(sentAt) ?: 0L
        val respondedAtMs = respondedAt?.let { parseIso(it) }
        return ComplianceReportItem(
            id = id,
            title = compliance.title,
            description = compliance.description ?: "",
            complianceType = compliance.complianceType,
            isAcknowledged = status != "pending",
            createdAt = sentAtMs,
            status = status,
            respondedAt = respondedAtMs
        )
    }

    private fun showStartDatePicker() {
        val utc = TimeZone.getTimeZone("UTC")
        val cal = startCalendar ?: Calendar.getInstance(utc)
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                startCalendar = Calendar.getInstance(utc).apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                mBinding.startDateEt.setText(displayDateFormat.format(startCalendar!!.time))
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showEndDatePicker() {
        val utc = TimeZone.getTimeZone("UTC")
        val cal = endCalendar ?: Calendar.getInstance(utc)
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                endCalendar = Calendar.getInstance(utc).apply {
                    set(year, month, day, 23, 59, 59)
                    set(Calendar.MILLISECOND, 999)
                }
                mBinding.endDateEt.setText(displayDateFormat.format(endCalendar!!.time))
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
        val token = SharedPref.getInstance(requireContext())?.getToken() ?: ""
        val startIso = isoFormat.format(startCalendar!!.time)
        val endIso = isoFormat.format(endCalendar!!.time)
        mainViewModel.getFieldWorkerCompliance(startIso, endIso, currentPage, PAGE_SIZE, token)
    }

    private fun loadMoreItems() {
        if (isLoadingMore || !hasMorePages) return
        isLoadingMore = true
        currentPage++
        loadReports(reset = false)
    }

    private fun onEmpty() {
        hideShimmer()
        mBinding.swipeRefresh.isRefreshing = false
        showErrorView(
            getString(R.string.no_reports_found),
            getString(R.string.no_reports_message),
            showRetry = false
        )
    }

    private fun onError(showRetry: Boolean = true) {
        hideShimmer()
        mBinding.swipeRefresh.isRefreshing = false
        showErrorView(
            getString(R.string.error_loading_reports),
            getString(R.string.network_error_message),
            showRetry
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
        // Already acknowledged on server
        if (item.isAcknowledged) return
        // Acknowledged locally, waiting for server confirmation — block re-submission
        if (item.isPendingSync) {
            android.widget.Toast.makeText(
                requireContext(),
                "Already acknowledged. Waiting for server sync.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        val ageMs = System.currentTimeMillis() - item.createdAt
        if (ageMs <= FIFTEEN_MINUTES_MS) {
            val intent = Intent(requireContext(), ComplaintAlertActivity::class.java).apply {
                putExtra(com.shiftsmart.plus.ui.activities.MainActivity.EXTRA_COMPLIANCE_RECORD_ID, item.id)
                putExtra(ComplaintAlertActivity.EXTRA_TITLE, item.title)
                putExtra(ComplaintAlertActivity.EXTRA_DESCRIPTION, item.description)
                putExtra(ComplaintAlertActivity.EXTRA_SENT_AT_MS, item.createdAt)
            }
            startActivity(intent)
        } else {
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
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            requireContext().getColor(android.R.color.holo_red_dark)
        )
    }
}
