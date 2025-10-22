package com.shiftsmart.plus.ui.fragments.errors

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shiftsmart.plus.R
import com.shiftsmart.plus.database.DBDao
import com.shiftsmart.plus.database.ShiftSmartPlusDatabase
import com.shiftsmart.plus.databinding.FragmentErrorsSolutionsBinding
import com.shiftsmart.plus.models.AppIssue
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ErrorsSolutionsFragment : Fragment() {

    private val TAG = "ErrorsSolutionsFragment"
    private lateinit var mBinding: FragmentErrorsSolutionsBinding

    @Inject
    lateinit var db: ShiftSmartPlusDatabase
    private lateinit var dao: DBDao

    private lateinit var adapter: AppIssueAdapter

    private val issueList = listOf(
        AppIssue("battery_saver_on", "Battery Saver switched On", "Go to Settings > Battery, disable all battery savers and managers for Shift Smart+."),
        AppIssue("location_off", "Location switched On", "Make sure location is ON and set to ACCURATE in settings > connections."),
        AppIssue("mobile_data_off", "Mobile data switched off", "Make sure mobile data is ON in settings > connections."),
        AppIssue("wifi_off", "Wi-Fi switched On", "Make sure Wi-Fi is ON in settings > connections."),
        AppIssue("background_restricted", "App allowed to run in the Background", "Go to Settings > Apps > Shift Smart+ > Background Usage > disable 'Put unused apps to sleep'."),
        AppIssue("notification_off", "Notifications not allowed", "Go to Settings > Apps > Shift Smart+ > Notifications > enable all notifications."),
        AppIssue("permissions_removed", "App not running every 5min - Remove permissions if unused", "Go to Settings > Apps > Shift Smart+ > Permissions > disable 'Remove permissions if unused'."),
        AppIssue("battery_optimization_on", "Battery optimization Active", "Go to Settings > Battery > App Standby Optimizer > Shift Smart+ > disable optimization."),
        AppIssue("app_cache_issue", "All settings checked but still offline", "Go to Settings > Apps > Shift Smart+ > Storage > clear cache/data, then relogin."),
        AppIssue("internet_off", "No signal or mobile/wifi connectivity", "Move to a better signal area and reopen Shift Smart+ to sync offline data.")
    )

    // Automatically extract all issue keys
    private val allKnownIssues = issueList.map { it.key }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        mBinding = FragmentErrorsSolutionsBinding.inflate(inflater, container, false)
        dao = db.dbDao()

        setupRecyclerView()
        loadIssuesFromDbAndUpdateUI()

        mBinding.backArrow.setOnClickListener {
            findNavController().navigateUp()
        }

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
    }
    private fun setupRecyclerView() {
        adapter = AppIssueAdapter(emptyList())
        mBinding.errorsRecyclerview.layoutManager = LinearLayoutManager(requireContext())
        mBinding.errorsRecyclerview.adapter = adapter
    }

    private fun loadIssuesFromDbAndUpdateUI() {
        lifecycleScope.launch {
            val user=SharedPref.getInstance(requireContext())?.getUser()
            val savedIssues = dao.getAllIssues(user?._id.toString()) // List<IssueEntity>
            val savedKeys = savedIssues.map { it.issueKey }

            val updatedIssues = issueList.map { issue ->
                if (savedKeys.contains(issue.key)) {
                    issue.copy(isIssue = true)
                } else {
                    issue.copy(isIssue = false)
                }
            }

            adapter.updateList(updatedIssues)
            when {
                savedKeys.isEmpty() -> {
                    // No issues found
                    mBinding.errorsBtn.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.green))
                    mBinding.errorsBtn.text = getString(R.string.everything_looks_good)
                }

                // Case 2: Only "wifi_off" or only "mobile_data_off" is present, no other issues
                (savedKeys.contains("wifi_off") && savedKeys.size == 1) ||
                        (savedKeys.contains("mobile_data_off") && savedKeys.size == 1) -> {
                    mBinding.errorsBtn.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.green))
                    mBinding.errorsBtn.text = getString(R.string.everything_looks_good)
                }

                savedKeys.containsAll(allKnownIssues) -> {
                    // All possible issues found → Critical
                    mBinding.errorsBtn.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.red))
                    mBinding.errorsBtn.text = "App Operation Error (Critical Issues)"
                }


                else -> {
                    // Some (not all) issues found → Potential
                    mBinding.errorsBtn.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.orange))
                    mBinding.errorsBtn.text = "App Operation Error (Potential Issues)"
                }
            }
        }
    }
}
