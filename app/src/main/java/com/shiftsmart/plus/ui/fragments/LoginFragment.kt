package com.shiftsmart.plus.ui.fragments

import android.Manifest
import android.app.AlarmManager
import com.shiftsmart.plus.models.LoginRequest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.LocationManager
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.gson.Gson
import com.shiftsmart.plus.R
import com.shiftsmart.plus.databinding.FragmentLoginBinding
import com.shiftsmart.plus.databinding.LoadingDialogBinding
import com.shiftsmart.plus.periodicAction.AlarmScheduler
import com.shiftsmart.plus.services.LocationTrack
import com.shiftsmart.plus.utils.PasswordToggleHandler
import com.shiftsmart.plus.utils.Resource
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LoginFragment : Fragment() {

    private val TAG = "LoginFragment"
    private lateinit var mBinding: FragmentLoginBinding

    val mainViewModel: MainViewModel by viewModels()

    private var mProgressDialog: Dialog? = null

    @Inject
    lateinit var locationTrack: LocationTrack

    @Inject
    lateinit var locationManager: LocationManager

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
                                ?.run {
                                    findNavController().navigate(R.id.action_loginFragment_to_homeFragment)
                                }
                            // use here workmanager
                            // 🔹 Schedule WorkManager for periodic API calls
                            it.data?.userModel?.timetable?.range?.let { it1 ->
                                AlarmScheduler.scheduleAlarms(requireContext(),
                                    it1
                                )
                            }

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
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mBinding.loginBtn.isEnabled = false


        val passwordToggleHandler =
            PasswordToggleHandler(mBinding.passwordEdittext, mBinding.passwordToggle)
        passwordToggleHandler.setupPasswordToggle()

        mBinding.loginBtn.setOnClickListener {

            if (!Utils.isIgnoringBatteryOptimizations(requireContext())) {
                requestIgnoreBatteryOptimization(requireContext())
            }else{
                if (checkAndRequestPermissions())
                {
                    doLoginOperations()
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
    private fun checkAndRequestPermissions(): Boolean {
        Log.i(TAG, "checkAndRequestPermissions: Checking permissions...")

        // 🔹 First, Check Exact Alarm Permission (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                startActivity(intent)
                return false
            }
        }

        val permissionsNeeded = mutableListOf<String>()

        // 🔹 Check Post Notification Permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 🔹 Check Foreground Service Permission (Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.FOREGROUND_SERVICE) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.FOREGROUND_SERVICE)
        }

        // 🔹 Check Location Permissions
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // 🔹 Check Background Location Permission (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        Log.i(TAG, "checkAndRequestPermissions: Permissions to request: $permissionsNeeded")

        return if (permissionsNeeded.isNotEmpty()) {
            requestPermissions(permissionsNeeded.toTypedArray(), 100)
            false
        } else {
            Log.i(TAG, "checkAndRequestPermissions: All permissions already granted.")
            true  // ✅ All permissions granted
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        Log.i(TAG, "onRequestPermissionsResult: requestCode = $requestCode, permissions = ${permissions.joinToString()}, grantResults = ${grantResults.joinToString()}")

        if (requestCode == 100) {
            if (grantResults.isNotEmpty()) {
                var fineLocationGranted = false
                var backgroundLocationGranted = false

                for (i in permissions.indices) {
                    when (permissions[i]) {
                        Manifest.permission.ACCESS_FINE_LOCATION -> fineLocationGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION -> backgroundLocationGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED
                    }
                }

                Log.i(TAG, "onRequestPermissionsResult: Fine Location = $fineLocationGranted, Background Location = $backgroundLocationGranted")

                if (fineLocationGranted && backgroundLocationGranted) {
                    Log.i(TAG, "onRequestPermissionsResult: ✅ All required permissions granted. Proceeding with login.")
                    doLoginOperations()
                } else if (fineLocationGranted && !backgroundLocationGranted) {
                    Log.i(TAG, "onRequestPermissionsResult: ❌ Background Location permission missing. Prompting user.")
                    Toast.makeText(requireContext(), "Background location permission is required for full functionality.", Toast.LENGTH_SHORT).show()
                    openAppSettings()
                } else {
                    Log.i(TAG, "onRequestPermissionsResult: ❌ Permissions denied. Cannot proceed.")
                    Toast.makeText(requireContext(), "Permissions are required to proceed.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", requireContext().packageName, null)
        intent.data = uri
        startActivity(intent)
    }

}