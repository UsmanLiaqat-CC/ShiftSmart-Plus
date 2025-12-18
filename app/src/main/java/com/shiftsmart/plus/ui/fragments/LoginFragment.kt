package com.shiftsmart.plus.ui.fragments

import com.shiftsmart.plus.models.LoginRequest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.firebase.messaging.FirebaseMessaging
import com.google.gson.Gson
import com.shiftsmart.plus.R
import com.shiftsmart.plus.databinding.FragmentLoginBinding
import com.shiftsmart.plus.databinding.LoadingDialogBinding
import com.shiftsmart.plus.periodicAction.AlarmScheduler
import com.shiftsmart.plus.utils.PasswordToggleHandler
import com.shiftsmart.plus.utils.PermissionHandler
import com.shiftsmart.plus.utils.Resource
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LoginFragment : Fragment() {

    private val TAG = "LoginFragment"
    private lateinit var mBinding: FragmentLoginBinding

    val mainViewModel: MainViewModel by viewModels()

    private var mProgressDialog: Dialog? = null

    private lateinit var permissionHandler: PermissionHandler


    private lateinit var progressDialogBinding: LoadingDialogBinding
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment

        mBinding = FragmentLoginBinding.inflate(inflater, container, false)
        setUpProgressDialog()
        setUpObserver()
        return mBinding.root

    }


    fun requestIgnoreBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:${context.packageName}")
                context.startActivity(intent)
            }
        }
    }
    private fun doLoginOperations() {
        if (isValid()) {
            if (Utils.isInternetAvailable(requireContext())) {
                val loginRequest = LoginRequest(
                    userName = mBinding.emailEdittext?.text.toString().trim(),
                    password = mBinding.passwordEdittext?.text.toString().trim(),
                )
                mainViewModel.loginUser(loginRequest)
            } else {
                Utils.showSnackBar(getString(R.string.no_network_connection), mBinding.root)
            }

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
                    if (mProgressDialog != null && mProgressDialog?.isShowing == false) {
                        mProgressDialog?.show()
                    }
                }

                is Resource.Success -> {

                    val userResponse = resource.data
                    Log.i(TAG, "setUpObserver: userResponse:${Gson().toJson(userResponse)}")

                    if (mProgressDialog?.isShowing == true) {
                        mProgressDialog?.dismiss()
                    }

                    userResponse.let {
                        Utils.showSnackBar(getString(R.string.login_successfully), mBinding.root)
                        if (it.data?.userModel?.isActive == true) {

                            Log.i(TAG, "setUpObserver: Token:${it.data?.accessToken}")
                            Log.i(TAG, "setUpObserver: user:${it.data?.userModel}")
                            SharedPref.getInstance(requireContext())?.saveToken(it.data.accessToken)
                            SharedPref.getInstance(requireContext())?.saveUser(it.data?.userModel)

                            FirebaseMessaging.getInstance().subscribeToTopic(it.data?.userModel?._id.toString())

                            // ✅ IMPORTANT: Only schedule alarms if basic permissions are granted
                            // This prevents crash when trying to start foreground service without permissions
                            if (permissionHandler.hasBasicLoginPermissions()) {
                                Log.i(TAG, "✅ Permissions granted, scheduling alarms and starting service")
                                val defaultShifts = it.data?.userModel?.timetable?.range
                                val multiTimeTables = it.data?.userModel?.multipleTimeTables

                                AlarmScheduler.scheduleAlarms(
                                    context = requireContext(),
                                    defaultShifts = defaultShifts!!,
                                    multipleTimeTables = multiTimeTables!!
                                )
                            } else {
                                Log.i(TAG, "⚠️ Permissions not granted yet, will schedule alarms from HomeFragment")
                            }

                            // Navigate to home
                            findNavController().navigate(R.id.action_loginFragment_to_homeFragment)

                        } else {
                            Utils.showSnackBar(
                                getString(R.string.user_is_not_active_please_contact_your_supervisor),
                                mBinding.root
                            )
                        }
                    }
                }

                is Resource.Error -> {
                    if (mProgressDialog?.isShowing == true) {
                        mProgressDialog?.dismiss()
                    }
                    Log.i(TAG, "setUpObserver: error:${resource.message}")

                    Utils.showSnackBar(resource.message, mBinding.root)
                }

                else -> {}
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionHandler.handlePermissionResult(result)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mBinding.loginBtn.isEnabled = false

        // Initialize permission handler
        permissionHandler = PermissionHandler(
            fragment = this,
            onPermissionsGranted = {
                Log.i(TAG, "✅ Permissions granted, proceeding with login")
                doLoginOperations()
            },
            onPermissionsDenied = { deniedPermissions ->
                Log.i(TAG, "⚠️ Some permissions denied: $deniedPermissions. Proceeding with login anyway.")
                val message = permissionHandler.getDeniedPermissionsMessage(deniedPermissions)
                if (message.isNotEmpty()) {
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
                // Still allow login even if permissions are denied
                doLoginOperations()
            }
        )
        permissionHandler.initializePermissionLauncher(permissionLauncher)

        val passwordToggleHandler =
            PasswordToggleHandler(mBinding.passwordEdittext, mBinding.passwordToggle)
        passwordToggleHandler.setupPasswordToggle()

        mBinding.loginBtn.setOnClickListener {

            if (!Utils.isIgnoringBatteryOptimizations(requireContext())) {
                requestIgnoreBatteryOptimization(requireContext())
            } else {
                // Request only basic login permissions (POST_NOTIFICATIONS, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)
                // Advanced permissions (background location, battery settings) will be handled at Home screen
                Log.i(TAG, "onViewCreated: login button pressed, checking basic login permissions")
                permissionHandler.requestLoginPermissions()
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

    private fun isValid(): Boolean {
        var result = true
        if (TextUtils.isEmpty(mBinding.emailEdittext?.text.toString().trim())) {
            mBinding.emailEdittext.error = getString(R.string.required)
            result = false
        } else if (TextUtils.isEmpty(mBinding.passwordEdittext?.text.toString().trim())) {
            mBinding.passwordEdittext.error = getString(R.string.required)
            result = false
        }
        return result
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionHandler.handlePermissionResult(requestCode, permissions, grantResults)
    }

}