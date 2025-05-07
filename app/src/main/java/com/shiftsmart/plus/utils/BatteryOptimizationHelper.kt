package com.shiftsmart.plus.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.shiftsmart.plus.R
import android.content.ComponentName
import android.net.Uri
import android.provider.Settings
import androidx.annotation.RequiresApi

object BatteryOptimizationHelper {

    fun checkBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                handleDeviceSpecificOptimizations(context)
            }
        }
    }

    private fun handleDeviceSpecificOptimizations(context: Context) {
        when {
            Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) -> handleXiaomiOptimization(context)
            Build.MANUFACTURER.equals("samsung", ignoreCase = true) -> handleSamsungOptimization(context)
            Build.MANUFACTURER.equals("huawei", ignoreCase = true) -> handleHuaweiOptimization(context)
            Build.MANUFACTURER.equals("oppo", ignoreCase = true) -> handleOppoOptimization(context)
            Build.MANUFACTURER.equals("vivo", ignoreCase = true) -> handleVivoOptimization(context)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> requestIgnoreBatteryOptimizations(context)
            else -> Log.d("BatteryOpt", "No special optimization needed for ${Build.MANUFACTURER}")
        }
    }

    private fun handleXiaomiOptimization(context: Context) {
        try {
            val intent = Intent("miui.intent.action.APP_AUTOSTART_MANAGE").apply {
                setPackage("com.miui.securitycenter")
                putExtra("package_name", context.packageName)
                putExtra("package_label", context.getString(R.string.app_name))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent().apply {
                    component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    putExtra("package_name", context.packageName)
                    putExtra("package_label", context.getString(R.string.app_name))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("BatteryOpt", "Xiaomi optimization failed", e)
                requestIgnoreBatteryOptimizations(context)
            }
        }
    }

    private fun handleHuaweiOptimization(context: Context) {
        try {
            val intents = listOf(
                Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
                    putExtra("package_name", context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            for (intent in intents) {
                try {
                    context.startActivity(intent)
                    return
                } catch (e: Exception) {
                    continue
                }
            }
            requestIgnoreBatteryOptimizations(context)
        } catch (e: Exception) {
            Log.e("BatteryOpt", "Huawei optimization failed", e)
            requestIgnoreBatteryOptimizations(context)
        }
    }

    private fun handleSamsungOptimization(context: Context) {
        try {
            val intents = listOf(
                Intent().apply {
                    component = ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent().apply {
                    component = ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            for (intent in intents) {
                try {
                    context.startActivity(intent)
                    return
                } catch (e: Exception) {
                    continue
                }
            }
            requestIgnoreBatteryOptimizations(context)
        } catch (e: Exception) {
            Log.e("BatteryOpt", "Samsung optimization failed", e)
            requestIgnoreBatteryOptimizations(context)
        }
    }

    private fun handleOppoOptimization(context: Context) {
        try {
            val intents = listOf(
                Intent().apply {
                    component = ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent().apply {
                    component = ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.PowerConsumptionActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            for (intent in intents) {
                try {
                    context.startActivity(intent)
                    return
                } catch (e: Exception) {
                    continue
                }
            }
            requestIgnoreBatteryOptimizations(context)
        } catch (e: Exception) {
            Log.e("BatteryOpt", "Oppo optimization failed", e)
            requestIgnoreBatteryOptimizations(context)
        }
    }

    private fun handleVivoOptimization(context: Context) {
        try {
            val intents = listOf(
                Intent().apply {
                    component = ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent().apply {
                    component = ComponentName("com.vivo.abe", "com.vivo.energy.EnergyManagerActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            for (intent in intents) {
                try {
                    context.startActivity(intent)
                    return
                } catch (e: Exception) {
                    continue
                }
            }
            requestIgnoreBatteryOptimizations(context)
        } catch (e: Exception) {
            Log.e("BatteryOpt", "Vivo optimization failed", e)
            requestIgnoreBatteryOptimizations(context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("BatteryOpt", "Failed to request ignore battery optimizations", e)
        }
    }
}
