package com.shiftsmart.plus.ui.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import com.shiftsmart.plus.R
import com.shiftsmart.plus.databinding.FragmentProfileBinding
import com.shiftsmart.plus.utils.SharedPref

class ProfileFragment : Fragment() {

    private  val TAG = "ProfileFragment"
    private lateinit var mBinding:FragmentProfileBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment

        mBinding=FragmentProfileBinding.inflate(inflater,container,false)

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

        setDataonViews()

        mBinding.backArrow.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setDataonViews() {
        val user= SharedPref.getInstance(requireContext())?.getUser()

        user?.let {
            mBinding.nameTv.text=it.name+" "+it.surName
            mBinding.orgTv.text=it.organization?.name?:""
            mBinding.administatorTv.text=it.administrator?.name?:""
            mBinding.businessUnitTv.text = it.businessUnit.joinToString(", ") { bu -> bu.name }
            mBinding.generalManagerTv.text=it.generalManager?.name?:""
            mBinding.reginoalManagerTv.text=it.regionalManager?.name?:""
            mBinding.supervisorTv.text=it.managerID?.name?:""
        }
    }

}