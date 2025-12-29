package com.shiftsmart.plus.utils

import android.app.AlertDialog
import android.content.Context

import android.util.Log
import android.view.LayoutInflater
import androidx.fragment.app.Fragment
import com.shiftsmart.plus.databinding.DialogLocationDisclosureBinding

/**
 * Centralized Location Disclosure Manager
 *
 * This class handles the prominent disclosure of location data usage as required by
 * Google Play Policy. It must be shown BEFORE requesting location permissions.
 *
 * Purpose:
 * - Disclose what location data is collected
 * - Explain how location data is used
 * - Explain why "Allow all the time" permission is required
 * - Comply with Google Play Store requirements
 */
class LocationDisclosureManager(
    private val fragment: Fragment,
    private val onAccepted: () -> Unit,
    private val onDeclined: (() -> Unit)? = null
) {
    private val TAG = "LocationDisclosure"
    private val context: Context = fragment.requireContext()

    companion object {
        // SharedPreferences key to track if user has seen disclosure
        private const val PREF_NAME = "location_disclosure_prefs"
        private const val KEY_DISCLOSURE_SHOWN = "disclosure_shown_v1"
        private const val KEY_DISCLOSURE_ACCEPTED = "disclosure_accepted_v1"

        /**
         * Check if user has already accepted the disclosure
         */
        fun hasAcceptedDisclosure(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_DISCLOSURE_ACCEPTED, false)
        }

        /**
         * Reset disclosure acceptance (useful for testing or policy updates)
         */
        fun resetDisclosure(context: Context) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean(KEY_DISCLOSURE_SHOWN, false)
                putBoolean(KEY_DISCLOSURE_ACCEPTED, false)
                apply()
            }
        }
    }

    /**
     * Show the prominent disclosure dialog
     * This MUST be called BEFORE requesting location permissions
     */
    fun showDisclosure() {
        Log.i(TAG, "📍 Showing Location Data Disclosure")

        val builder = AlertDialog.Builder(context)
        val inflater = LayoutInflater.from(context)
        val binding = DialogLocationDisclosureBinding.inflate(inflater)

        // Set the disclosure message
        binding.disclosureMessage.text = buildDisclosureMessage()

        val dialog = builder.setView(binding.root)
            .setCancelable(false)
            .create()

        // Set transparent background to show rounded corners properly
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Set dialog dimensions to prevent fullscreen mode
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // Accept button
        binding.acceptButton.setOnClickListener {
            Log.i(TAG, "✅ User accepted location disclosure")
            markDisclosureAccepted()
            dialog.dismiss()
            onAccepted()
        }

        // Decline button
        binding.declineButton.setOnClickListener {
            Log.i(TAG, "❌ User declined location disclosure")
            dialog.dismiss()
            onDeclined?.invoke()
        }

        dialog.show()
    }

    /**
     * Build the detailed disclosure message
     */
    private fun buildDisclosureMessage(): String {
        return """
Location Data Usage Disclosure

This app collects and uses your location data to:

• Track your work shift attendance
• Verify you are at the designated work location
• Record check-in and check-out times with location stamps
• Monitor location throughout your shift for work verification
• Ensure accurate timesheet and payroll processing

Important Information:

Background Location Access ("Allow all the time"):
This app requires "Allow all the time" location permission because it needs to track your location continuously during your work shifts, even when the app is not actively open on your screen. This ensures:
• Automatic shift tracking without manual intervention
• Accurate location verification during your entire shift
• Reliable attendance monitoring
• Proper functioning of shift reminders and alerts

Data Collection:
• GPS coordinates are collected during active work shifts
• Location data is sent to our secure servers
• Data is used exclusively for work attendance verification and payroll
• Location tracking only occurs during scheduled work hours

Your Privacy:
• Location data is encrypted and securely transmitted
• Data is only accessible to authorized personnel
• You can review our full Privacy Policy for more details

By accepting, you consent to the collection and use of your location data as described above.
        """.trimIndent()
    }

    /**
     * Mark that user has accepted the disclosure
     */
    private fun markDisclosureAccepted() {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_DISCLOSURE_SHOWN, true)
            putBoolean(KEY_DISCLOSURE_ACCEPTED, true)
            apply()
        }
    }

    /**
     * Show disclosure only if not previously accepted
     * @return true if disclosure needs to be shown, false if already accepted
     */
    fun showDisclosureIfNeeded(): Boolean {
        if (hasAcceptedDisclosure(context)) {
            Log.i(TAG, "✅ User has already accepted disclosure, proceeding directly")
            onAccepted()
            return false
        } else {
            showDisclosure()
            return true
        }
    }
}

