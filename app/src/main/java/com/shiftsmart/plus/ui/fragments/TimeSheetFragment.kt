package com.shiftsmart.plus.ui.fragments

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.shiftsmart.plus.databinding.FragmentTimeSheetBinding
import com.shiftsmart.plus.utils.Resource
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TimeSheetFragment : Fragment() {

    val mainViewModel: MainViewModel by viewModels()
    private  val TAG = "TimeSheetFragment"
    private lateinit var mBinding: FragmentTimeSheetBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        mBinding=FragmentTimeSheetBinding.inflate(inflater,container,false)

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

        observeViewModel()


        val user = SharedPref.getInstance(requireContext())?.getUser()
        user?.let { it ->
            mainViewModel.getTimeSheet(
                userId = it._id,
            )
        }

        mBinding.backArrow.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun observeViewModel() {
        mainViewModel.timeSheetResponse.observe(viewLifecycleOwner) { resource ->
            when (resource) {
                is Resource.Loading -> {
                    mBinding.progressBar.visibility = View.VISIBLE

                    setDataonViews("",0,0)
                }
                is Resource.Success -> {
                    
                    mBinding.progressBar.visibility = View.GONE
                    val timeSheetModel = resource.data
                    Log.i(TAG, "observeViewModel: timeSheetModel - day: ${timeSheetModel.day}, totalHours: ${timeSheetModel.totalHours}, attendanceInMin: ${timeSheetModel.attendanceInMin}")

                    setDataonViews(timeSheetModel.day,timeSheetModel.totalHours,timeSheetModel.attendanceInMin)
                }
                is Resource.Error -> {
                    mBinding.progressBar.visibility = View.GONE
                    setDataonViews("",0,0)
                    Utils.showSnackBar(resource.message ?: "Error", mBinding.root)
                }
            }
        }
    }

    private fun setDataonViews(currnetDay: String, totalHours: Int, spentHours: Int) {
        mBinding.currentDayTv.text=currnetDay
        mBinding.totalHoursTv.text=totalHours.toString()+" Hours"
        mBinding.spendingTv.text=convertMinutesToHours(spentHours)
    }

    fun convertMinutesToHours(minutes: Int): String {
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        return "${hours}h ${remainingMinutes}m"
    }


}

