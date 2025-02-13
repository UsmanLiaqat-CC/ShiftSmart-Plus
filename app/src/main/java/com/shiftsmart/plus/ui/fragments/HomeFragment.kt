package com.shiftsmart.plus.ui.fragments

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.shiftsmart.plus.R
import com.shiftsmart.plus.database.DBDao
import com.shiftsmart.plus.database.RecordModel
import com.shiftsmart.plus.database.ShiftSmartPlusDatabase
import com.shiftsmart.plus.databinding.CustomAlertDialogBinding
import com.shiftsmart.plus.databinding.FragmentHomeBinding
import com.shiftsmart.plus.databinding.LoadingDialogBinding
import com.shiftsmart.plus.databinding.LogoutDialogBinding
import com.shiftsmart.plus.enums.StatusEnum
import com.shiftsmart.plus.models.AttendaceResponseModel
import com.shiftsmart.plus.models.DataRequest
import com.shiftsmart.plus.models.WifiModel
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.repository.MainRepository
import com.shiftsmart.plus.services.LocationTrack
import com.shiftsmart.plus.utils.BatteryOptimizationContract
import com.shiftsmart.plus.utils.ButtonActionEnum
import com.shiftsmart.plus.utils.Constants.MY_PERMISSIONS_REQUEST_LOCATION
import com.shiftsmart.plus.utils.Constants.MY_PERMISSIONS_REQUEST_NOTIFICATION
import com.shiftsmart.plus.utils.PermissionHandler
import com.shiftsmart.plus.utils.Resource
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.getCurrentDateTime
import com.shiftsmart.plus.utils.Utils.isServiceRunning
import com.shiftsmart.plus.utils.WifiScanner
import com.shiftsmart.plus.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class HomeFragment : Fragment() {


    private  val TAG = "HomeFragment"
    private lateinit var mBinding:FragmentHomeBinding
    private var logoutDialog: Dialog? = null  // Keep reference to avoid multiple dialogs

    private var mProgressDialog: Dialog?=null
    private lateinit var progressDialogBinding: LoadingDialogBinding
    val mainViewModel: MainViewModel by viewModels()
    @Inject
    lateinit var db : ShiftSmartPlusDatabase

    @Inject
    lateinit var locationTrack: LocationTrack

    @Inject
    lateinit var locationManager: LocationManager
    @Inject lateinit var repository: MainRepository

    private lateinit var dao: DBDao

    @Inject
    lateinit var wifiScanner: WifiScanner

    private var currentMessageRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private val mMsgInterval=5000L
    private lateinit var permissionHandler: PermissionHandler

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionHandler.handlePermissionsResult(permissions)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        mBinding=FragmentHomeBinding.inflate(inflater,container,false)

        Log.i(TAG, "onCreateView: ")
        return mBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dao = db.dbDao()
        // Initialize PermissionHandler
        // Initialize PermissionHandler with a callback
        permissionHandler = PermissionHandler(requireContext(), requireActivity()) {
            onPermissionsGranted() // Call this method when all permissions are granted
        }

        permissionHandler.registerPermissionLauncher(permissionLauncher)
        permissionHandler.checkPermissions()

        setUpProgressDialog()
        setUpClickListeners()
        setUpObserver()

        setChecksData()

    }

    private fun setChecksData() {

        val user=SharedPref.getInstance(requireContext())?.getUser()

        user?.let {
            mBinding.greetingText.text="${getString(R.string.hi)} ${it.name} ${it.surName}"
        }

        mBinding.internetStatusIcon.setImageResource(
            if (!Utils.isInternetAvailable(requireContext())) R.drawable.ic_not_check else R.drawable.ic_check
        )
        mBinding.wifiStatusIcon.setImageResource(
            if (!wifiScanner.isWifiEnabled()) R.drawable.ic_not_check else R.drawable.ic_check
        )
        mBinding.mobileDataStatusIcon.setImageResource(
            if (!Utils.isMobileDataEnabled(requireContext())) R.drawable.ic_not_check else R.drawable.ic_check
        )
        mBinding.locationServiceStatusIcon.setImageResource(
            if (!locationTrack.checkLocationPermissions()) R.drawable.ic_not_check else R.drawable.ic_check
        )
        mBinding.notificationStatusIcon.setImageResource(
            if (!Utils.isNotificationPermissionGranted(requireContext())) R.drawable.ic_not_check else R.drawable.ic_check
        )
        mBinding.batterySaverStatusIcon.setImageResource(
            if (!Utils.isBatterySaverOn(requireContext())) R.drawable.ic_not_check else R.drawable.ic_check
        )
        mBinding.batteryOptimiztionStatusIcon.setImageResource(
            if (!Utils.isBatteryOptimizationOff(requireContext())) R.drawable.ic_not_check else R.drawable.ic_check
        )

        Utils.checkCacheDataAvailability(requireContext(),mBinding.cacheStatusTv)
    }

    // This method is called once all permissions are granted
    private fun onPermissionsGranted() {
        setChecksData()
        // Proceed with the next step after all permissions are granted
        Toast.makeText(requireContext(), getString(R.string.all_permissions_granted), Toast.LENGTH_SHORT).show()
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

    private fun setUpClickListeners() {
        mBinding.profileBtn.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_profileFragment)
        }
        mBinding.logoutBtn.setOnClickListener {
           showLogoutDialog()
        }

        mBinding.checkInButton.setOnClickListener {

            setChecksData()

            if (Utils.isInternetAvailable(requireContext()))
            {
                if (locationTrack.checkLocationPermissions())
                {
                    fetchLocationData()
                }else{
                    checkandGrantLocationPermission()
                }
            }else{
                showProgressDialog(getString(R.string.saving_data_to_database))
                // handle offline case
                callApiData(lat = 0.0, lan = 0.0)
            }
        }
    }
    private fun checkandGrantLocationPermission() {

        val dialog = Dialog(requireActivity())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val dialogBinding = CustomAlertDialogBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.show()

        dialogBinding.negativeBtn.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.postiveBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                requestMultiplePermissionsLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
//                        Manifest.permission.FOREGROUND_SERVICE_LOCATION
                    )
                )
            }
            else
            {

                if (locationTrack.checkLocationPermissions())
                {
                    Log.i(TAG, "setupClickLiseteners:less then 33 permission granted")
                    fetchLocationData()
                }else{
                    Log.i(TAG, "setupClickLiseteners:less then 33 permission not granted")

                    requestPermissions(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ),
                        MY_PERMISSIONS_REQUEST_LOCATION
                    )
                }

            }
            dialog.dismiss()
        }
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
//        val foregroundLocationGranted = permissions[Manifest.permission.FOREGROUND_SERVICE_LOCATION] ?: false

        if (fineLocationGranted && coarseLocationGranted ) {
            Log.i(TAG, "All permissions granted")

            fetchLocationData()
        } else {
            Log.i(TAG, "Some permissions not granted")

            Utils.showSnackBar(
                getString(R.string.location_permission_not_granted),
                mBinding.root
            )
        }
    }

    private fun showLogoutDialog() {
        if (logoutDialog?.isShowing == true) return  // Prevent duplicate dialog

        val dialogBinding =
            LogoutDialogBinding.inflate(LayoutInflater.from(requireContext())) // Use ViewBinding

        logoutDialog = Dialog(requireContext()).apply {
            setContentView(dialogBinding.root) // Set root view from binding
            setCancelable(true)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            dialogBinding.btnLogoutConfirm.setOnClickListener {
                dismiss()  // Close dialog
                performLogout()  // Call logout function
            }

            dialogBinding.btnCloseDialog.setOnClickListener {
                dismiss()  // Close dialog
            }

            show()
        }
    }

    private fun performLogout() {
        lifecycleScope.launch {

            val user= SharedPref.getInstance(requireContext())?.getUser()
            val token= SharedPref.getInstance(requireContext())?.getToken()
            user?.let {
                if (Utils.isInternetAvailable(requireContext()))
                {
                    mainViewModel.logoutUser(user_token = token?:"", id = it.id?:"")

                }else{
                    showMessage(getString(R.string.no_network_connection))
                }
            }
        }

    }

    private fun showMessage(message: String) {
        currentMessageRunnable?.let {
            handler.removeCallbacks(it)
        }
        
        // Update the error message
        mBinding.statusTv.text = message

        // Create a new runnable to clear the message after 5 seconds
        currentMessageRunnable = Runnable {

            mBinding.statusTv.text = ""
//            enableButtons()
        }
        // Post the new runnable
        handler.postDelayed(currentMessageRunnable!!, mMsgInterval)
    }

    private fun showSnackBarMessage(message: String) {
        Snackbar.make(mBinding.statusTv, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun setUpObserver() {
        mainViewModel.sendDataResponse.observe(viewLifecycleOwner) { resource ->
            when (resource) {
                is Resource.Loading ->
                {

                }
                is Resource.Success -> {
                    val attendanceResponse = resource.data as AttendaceResponseModel
                    CoroutineScope(Dispatchers.Main).launch {
                        dismissProgressDialog()

                        // Iterate through attendance data list
                        attendanceResponse.data.forEach { attendance ->
                            Log.i(TAG, "setUpObserver: eachResponse:${attendance}")
                            when (attendance.attendanceStatus) {
                                "online" -> {
                                    // If status is "online", show the message or store info
                                    withContext(Dispatchers.Main) {
                                        if (attendance.store.isNotEmpty()) {
                                            showMessage(attendance.store)
                                        } else {
                                            showMessage(attendance.message)
                                        }
                                    }
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val uuid = attendance.UUID // Get UUID from response
                                        // Find and delete records with this UUID
                                        db.dbDao().deleteRecordByUuid(uuid)

                                    }
                                  
                                }
                                "offline" -> {
                                    // If status is "offline", delete corresponding record from database
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val uuid = attendance.UUID // Get UUID from response
                                        // Find and delete records with this UUID
                                        db.dbDao().deleteRecordByUuid(uuid)

//                                        withContext(Dispatchers.Main) {
//                                            showMessage("Record with UUID $uuid deleted.")
//                                        }
                                    }
                                }
                            }
                        }
                    }
                }


                is Resource.Error -> {
                    CoroutineScope(Dispatchers.Main).launch {
                        dismissProgressDialog()

                    }
                    Log.i(TAG, "setUpObserver: error:${resource.message}")

                    if (resource.message=="LOGOUT" || resource.message==getString(R.string.unauthorize))
                    {
                        deleteUserDataAndLogout()
                    }else{

                        if (resource.message.contains("No address associated with hostname"))
                        {
                            showMessage(getString(R.string.unable_to_connect_internet_right_now_please_try_again))
                        }else{
                            showMessage(resource.message)
                        }
                    }

                }

                else -> {}
            }
        }
        mainViewModel.logoutResponse.observe(viewLifecycleOwner) { resource ->
            when (resource) {
                is Resource.Loading ->
                {
                    showProgressDialog(resource.message)
                }
                is Resource.Success -> {

                    Utils.showSnackBar(getString(R.string.logout_successfully),mBinding.root)

                    dismissProgressDialog()
                    deleteUserDataAndLogout()
                }

                is Resource.Error -> {
                    dismissProgressDialog()
                    Log.i(TAG, "setUpObserver: error:${resource.message}")

                    if (resource.message==getString(R.string.unauthorize))
                    {
                        deleteUserDataAndLogout()
                    }else{
                        showSnackBarMessage(resource.message)
                    }

                }

                else -> {}
            }
        }
    }

    fun showProgressDialog(message:String) {
        progressDialogBinding.titleTv.text = message
        if (mProgressDialog != null && mProgressDialog?.isShowing == false) {
            mProgressDialog?.show()
        }
    }

    fun dismissProgressDialog() {
        if (mProgressDialog != null && mProgressDialog?.isShowing == true) {
            mProgressDialog?.dismiss()
        }
    }

    private fun deleteUserDataAndLogout() {

        lifecycleScope.launch {
            locationTrack.stopListener()
            dao.deleteAllRecords()
            if (isServiceRunning(requireContext(), MyService::class.java)) {
                Log.i("Service", "Service is running. Stopping it now.")
                requireContext().stopService(Intent(requireContext(), MyService::class.java))
            }
            SharedPref.getInstance(requireContext())?.clearPrefrence()
            findNavController().navigate(R.id.action_homeFragment_to_loginFragment)
        }
    }


    fun fetchLocationData() {

        val checkGPS = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val checkNetwork = Utils.isInternetAvailable(requireContext())

        Log.i(TAG, "fetchLocationData: checkNetwork:${checkNetwork}-->checkGps:${checkGPS}")

        if (checkGPS) {
            val locationTrack = LocationTrack(requireContext())
            val mLocationManager = requireActivity().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            showProgressDialog(getString(R.string.fetching_location))

            // Timeout handler to stop fetching if it takes too long
            val handler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                dismissProgressDialog()
                locationTrack.stopListener() // Stop location updates
                showMessage(getString(R.string.unable_to_fetch_location_please_try_again_later))
            }

            handler.postDelayed(timeoutRunnable, 30000) // Set timeout for 30 seconds


            locationTrack.getLocation(mLocationManager) { location ->
                handler.removeCallbacks(timeoutRunnable) // Cancel the timeout if location is retrieved

//                dismissProgressDialog() // Dismiss the loading indicator
                Log.i(TAG, "fetchLocationData: location: ${location?.latitude}-->${location?.longitude}")

                // Check if location is not null
                location?.let {
                    locationTrack.stopListener()
                    locationTrack.loc=null
                    // If a new location is retrieved, use it
                    mBinding.coordsStatusTv.text = "${it.latitude} , ${it.longitude}"
                    val dateInString = getCurrentDateTime().toString()
                    mBinding.lastUpdateStatusTv.text = dateInString
                    progressDialogBinding.titleTv.text = getString(R.string.saving_datato_server)
                    callApiData(it.latitude, it.longitude)
                    Log.i(TAG, "fetchLocationData: location not null: $it")
                } ?: run {
                    Log.i(TAG, "fetchLocationData: location null: $location")
                    dismissProgressDialog()
                    showMessage(getString(R.string.unable_to_fetch_location_please_try_again_later))
                }
            }
        } else {
            showAlert(ButtonActionEnum.GPS.name)
        }
    }

    private fun callApiData(lat:Double,lan:Double) {
        Log.i(TAG, "callApiData: ")
        var wifiList= listOf<WifiModel>()
        val wifiScanner = WifiScanner(requireContext())
        wifiScanner.scanWifiNetworks { scanResults ->

            wifiList = if (scanResults.isNotEmpty()) {
                scanResults.map { result ->
                    WifiModel(ssid = result.SSID, bssid = result.BSSID, strength = Utils.rssiToPercentage(result.level) )
                }
            } else{
                arrayListOf()
            }

            val user=SharedPref.getInstance(requireContext())?.getUser()

            user?.let {itit->

                val randomUid=Utils.generateRandomFourDigitUuid()
                val record=RecordModel(
                    uuid=randomUid,
                    user_id = itit?.id.toString(),
                    lat = lat,
                    lng = lan,
                    localTime = Utils.getCurrent24HourTime(),
                    time = Utils.getCurrentUtcTime(),
                    attendanceType =StatusEnum.arrival.name,
                    attendanceStatus = Utils.checkInternetAndSetStatus(requireContext()),
                    isForceAttendance = false,
                    isLocation = locationTrack.checkLocationPermissions(),
                    wifiService = wifiScanner.isWifiEnabled(),
                    dataService = Utils.isMobileDataEnabled(requireContext()),
                    notification = Utils.isNotificationPermissionGranted(requireContext()),
                    batterySaver = Utils.isBatterySaverOn(requireContext()),
                    batteryOptimization = Utils.isBatteryOptimizationOff(requireContext()),
                    wifi_list = wifiList
                )

                if (Utils.isInternetAvailable(requireContext())) {

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            // Step 1: Fetch all records from the database
                            val records = dao.getAllRecords(itit?.id.toString())

                            // Step 2: Convert each record into a DataRequest model
                            val listDataRequest = records.map { it.toDataRequest() }.toMutableList()
                            Log.i(TAG, "callApiData: listDataRequest from db: ${listDataRequest.size}")

                            // Step 3: Add the new record to the list
                            listDataRequest.add(record.toDataRequest())

                            // Step 4: Log the final list
                            Log.i(TAG, "callApiData: listDataRequest after adding object: ${listDataRequest.size}")

                            // Step 5: Get the auth token
                            val token = SharedPref.getInstance(requireContext())?.getToken() ?: ""

                            // **Step 6: Switch to Main thread before updating LiveData**
                            withContext(Dispatchers.Main) {
                                mainViewModel.sendAppData(listDataRequest, token)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error fetching records: ${e.message}")
                        }
                    }

                } else {
                    Log.i(TAG, "callApiData: saving to database: ${record}")

                    dismissProgressDialog()

                    // Ensure database insertion runs in a background thread
                    CoroutineScope(Dispatchers.IO).launch {
                        dao.insertRecord(record)

                        // Show message on Main thread after database insertion
                        withContext(Dispatchers.Main) {
                            showMessage(getString(R.string.offile_alert_message))
                        }
                    }
                }

            }?.run { 
                dismissProgressDialog()
                Log.i(TAG, "callApiData: user not found")
            }

        }

    }

    fun showAlert(type:String) {

        val dialog = Dialog(requireActivity())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val dialogBinding = CustomAlertDialogBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.show()
//        enableButtons()
        when(type)
        {
            ButtonActionEnum.GPS.name ->{

                dialogBinding.postiveBtn.text=getString(R.string.yes)
                dialogBinding.negativeBtn.text=getString(R.string.no)

                dialogBinding.titleTv.text=getString(R.string.gps_is_not_enabled)
                dialogBinding.desTv.text=getString(R.string.do_you_want_to_turn_on_gps)
                dialogBinding.negativeBtn.setOnClickListener {
                    dialog.dismiss()
                }
                dialogBinding.postiveBtn.setOnClickListener {
                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    startActivity(intent)
                    dialog.dismiss()
                }

            }
            ButtonActionEnum.INTERNET.name ->{

                dialogBinding.postiveBtn.text=getString(R.string.yes)
                dialogBinding.negativeBtn.text=getString(R.string.no)

                dialogBinding.titleTv.text=getString(R.string.no_network_connection)
                dialogBinding.desTv.text=getString(R.string.do_you_want_to_turn_on_network)
                dialogBinding.negativeBtn.setOnClickListener { dialog.dismiss() }
                dialogBinding.postiveBtn.setOnClickListener {
                    val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                    startActivity(intent)
                    dialog.dismiss()
                }

            }
            ButtonActionEnum.SERVICE.name ->{

                dialogBinding.postiveBtn.text=getString(R.string.allow)
                dialogBinding.negativeBtn.text=getString(R.string.deny)

                dialogBinding.titleTv.text=getString(R.string.disable_battery_optimization)
                dialogBinding.desTv.text=getString(R.string.allow_app_to_run_in_backgorund)
                dialogBinding.negativeBtn.setOnClickListener { dialog.dismiss() }
                dialogBinding.postiveBtn.setOnClickListener {
                    batteryOptimizationLauncher.launch(Unit)
                    dialog.dismiss()
                }

            }
            ButtonActionEnum.NOTIFICATION.name ->{
                dialogBinding.postiveBtn.text=getString(R.string.allow)
                dialogBinding.negativeBtn.text=getString(R.string.deny)

                dialogBinding.titleTv.text=getString(R.string.post_notification)
                dialogBinding.desTv.text=getString(R.string.please_allow_enable_post_notification)
                dialogBinding.negativeBtn.setOnClickListener {
                    dialog.dismiss()
                }
                dialogBinding.postiveBtn.setOnClickListener {
                    getNotificationPermission()
                    dialog.dismiss()
                }

            }
        }

    }

    private val batteryOptimizationLauncher = registerForActivityResult(BatteryOptimizationContract()) { isSuccess ->
        if (Utils.isIgnoringBatteryOptimizations(requireContext()))
        {
            Log.i(TAG, "battery_optimization is ignored: ")

        } else {
            Log.i(TAG, "battery_optimization is not ignored: ")

            // Battery optimization not disabled
            Utils.showSnackBar(getString(R.string.please_turn_off_battery_optimization_to_allow_run_app_in_background),mBinding.root)

        }
    }

    fun getNotificationPermission() {
        try {
            if (Build.VERSION.SDK_INT > 32) {
                ActivityCompat.requestPermissions(
                    requireActivity(), arrayOf<String>(Manifest.permission.POST_NOTIFICATIONS),
                    MY_PERMISSIONS_REQUEST_NOTIFICATION
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun RecordModel.toDataRequest(): DataRequest {
        return DataRequest(
            UUID = this.uuid,
            user_id = this.user_id,
            lat = this.lat,
            lng = this.lng,
            localTime = this.localTime,
            time = this.time,
            attendanceType = this.attendanceType,
            attendanceStatus = this.attendanceStatus,
            isForceAttendance = this.isForceAttendance,
            isLocation = this.isLocation,
            wifiService = this.wifiService,
            dataService = this.dataService,
            notification = this.notification,
            batterySaver = this.batterySaver,
            batteryOptimization = this.batteryOptimization,
            wifi_list = this.wifi_list
        )
    }

}