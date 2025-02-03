package com.shiftsmart.plus.ui.fragments

import com.shiftsmart.plus.models.LoginRequest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.shiftsmart.plus.R
import com.shiftsmart.plus.databinding.FragmentLoginBinding
import com.shiftsmart.plus.databinding.LoadingDialogBinding
import com.shiftsmart.plus.service.MyForegroundService
import com.shiftsmart.plus.utils.PasswordToggleHandler
import com.shiftsmart.plus.utils.Resource
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class LoginFragment : Fragment() {

    private  val TAG = "LoginFragment"
    private lateinit var mBinding:FragmentLoginBinding

    val mainViewModel: MainViewModel by viewModels()

    private var mProgressDialog: Dialog?=null
    private lateinit var progressDialogBinding: LoadingDialogBinding
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment

        mBinding= FragmentLoginBinding.inflate(inflater,container, false)
        setUpProgressDialog()
        setUpObserver()
        return mBinding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mBinding.loginBtn.isEnabled = false


        val passwordToggleHandler = PasswordToggleHandler(mBinding.passwordEdittext, mBinding.passwordToggle)
        passwordToggleHandler.setupPasswordToggle()
        mBinding.loginBtn.setOnClickListener {
            if (isValid())
            {
                if (Utils.isInternetAvailable(requireContext()))
                {
                    val loginRequest= LoginRequest(
                        userName = mBinding.emailEdittext?.text.toString().trim(),
                        password = mBinding.passwordEdittext?.text.toString().trim(),
                    )
                    mainViewModel.loginUser(loginRequest)
                }else{
                    Utils.showSnackBar(getString(R.string.no_network_connection),mBinding.root)
                }

            }
        }
        mBinding.acceptPolicyCheckbox.setOnCheckedChangeListener { _, isChecked ->
            // Enable the login button if the checkbox is checked
            mBinding.loginBtn.isEnabled = isChecked
        }

        // Open privacy policy link in browser
        mBinding.privacyPolicyText.setOnClickListener {
            Utils.showPrivacy(requireContext())
        }
    }

    private fun setUpProgressDialog(
    ) {
        if (mProgressDialog != null && mProgressDialog!!.isShowing) {
            return
        }
        val inflater = LayoutInflater.from(requireActivity())
        progressDialogBinding = LoadingDialogBinding.inflate(inflater)
        mProgressDialog = Dialog(requireContext())
        mProgressDialog?.setContentView(progressDialogBinding.root)
        mProgressDialog?.setCancelable(false)
        mProgressDialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

    }

    private fun setUpObserver() {
        mainViewModel.loginResponse.observe(viewLifecycleOwner) { resource ->
            when (resource) {
                is Resource.Loading -> {
                    progressDialogBinding.titleTv.text = resource.message
                    if (mProgressDialog!=null && mProgressDialog?.isShowing==false)
                    {
                        mProgressDialog?.show()
                    }
                }
                is Resource.Success -> {

                    val userResponse=resource.data
                    Log.i(TAG, "setUpObserver: userResponse:${Gson().toJson(userResponse)}")

                    if (mProgressDialog?.isShowing==true)
                    {
                        mProgressDialog?.dismiss()
                    }

                    userResponse.let {
                        Utils.showSnackBar(getString(R.string.login_successfully),mBinding.root)
//                        scheduleWorker()

                        if (it.data?.userModel?.isActive==true)
                        {
                            startMyForegroundService(requireContext())

                            Log.i(TAG, "setUpObserver: Token:${it.data?.accessToken}")
                            Log.i(TAG, "setUpObserver: user:${it.data?.userModel}")
                            SharedPref.getInstance(requireContext())?.saveToken(it.data.accessToken)
                            SharedPref.getInstance(requireContext())?.saveUser(it.data?.userModel)?.run {
                                findNavController().navigate(R.id.action_loginFragment_to_homeFragment)
                            }
                        }else{
                            Utils.showSnackBar(getString(R.string.user_is_not_active_please_contact_your_supervisor),mBinding.root)
                        }
                    }
                }

                is Resource.Error -> {
                    if (mProgressDialog?.isShowing==true)
                    {
                        mProgressDialog?.dismiss()
                    }
                    Log.i(TAG, "setUpObserver: error:${resource.message}")

                    Utils.showSnackBar( resource.message,mBinding.root)
                }

                else -> {}
            }
        }
    }
    fun startMyForegroundService(context: Context) {
        val intent = Intent(context, MyForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }



    private fun isValid(): Boolean {
        var result = true
        if (TextUtils.isEmpty(mBinding.emailEdittext?.text.toString().trim())) {
            mBinding.emailEdittext.error = getString(R.string.required)
            result = false
        }  else if (TextUtils.isEmpty(mBinding.passwordEdittext?.text.toString().trim())) {
            mBinding.passwordEdittext.error = getString(R.string.required)
            result = false
        }
        return result
    }
}