package com.shiftsmart.plus.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.biometric.BiometricManager

import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.shiftsmart.plus.R

object FingerprintHelper {



    fun isFingerprintEnabled(context: Context): Boolean {
      return  SharedPref.getInstance(context)?.isFingerprintEnabled() ?: false
    }

    fun setFingerprintEnabled(context: Context, enabled: Boolean) {
       SharedPref.getInstance(context)?.setFingerprintEnabled(enabled)
    }

    fun isFingerprintSupported(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
    }

    fun isFingerprintAvailable(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun openSecuritySettings(context: Context) {
        val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
        context.startActivity(intent)
    }

    fun authenticate(activity: FragmentActivity, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    onError(activity.resources.getString(R.string.authentication_failed))
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.resources.getString(R.string.fingerprint_authentication))
            .setSubtitle(activity.resources.getString(R.string.confirm_with_fingerprint_to_continue))
            .setNegativeButtonText(activity.resources.getString(R.string.cancel))
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
