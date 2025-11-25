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
            Build.MANUFACTURER.equals("mara", ignoreCase = true) -> handleMaraOptimization(context)
            Build.MANUFACTURER.equals("mobicel", ignoreCase = true) -> handleMobicelOptimization(context)
            Build.MODEL.contains("mara", ignoreCase = true) -> handleMaraOptimization(context)
            Build.MODEL.contains("mobicel", ignoreCase = true) -> handleMobicelOptimization(context)
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

    /**
     * Handle Mara device battery optimization
     * Mara phones are budget Android devices (manufactured in Africa)
     * They typically use stock Android but with aggressive battery management
     */
    private fun handleMaraOptimization(context: Context) {
        Log.i("BatteryOpt", "Handling Mara device optimization (${Build.MODEL})")
        try {
            // Try standard Android battery optimization settings
            val intents = listOf(
                // Standard battery optimization
                Intent().apply {
                    action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                // App battery usage
                Intent().apply {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                // Generic battery settings
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )

            for (intent in intents) {
                try {
                    context.startActivity(intent)
                    Log.i("BatteryOpt", "Successfully opened Mara battery settings")
                    return
                } catch (e: Exception) {
                    Log.w("BatteryOpt", "Mara intent failed: ${intent.action}", e)
                    continue
                }
            }

            // Final fallback
            requestIgnoreBatteryOptimizations(context)
        } catch (e: Exception) {
            Log.e("BatteryOpt", "Mara optimization failed completely", e)
            requestIgnoreBatteryOptimizations(context)
        }
    }

    /**
     * Handle Mobicel device battery optimization
     * Mobicel phones are South African budget devices
     * They use heavily modified Android with aggressive power management
     */
    private fun handleMobicelOptimization(context: Context) {
        Log.i("BatteryOpt", "Handling Mobicel device optimization (${Build.MODEL})")
        try {
            // Mobicel uses custom battery management similar to Chinese OEMs
            val intents = listOf(
                // Try generic power manager
                Intent().apply {
                    action = "android.settings.POWER_USAGE_SUMMARY"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                // Try autostart settings
                Intent().apply {
                    component = ComponentName("com.android.settings", "com.android.settings.Settings\$AutoStartActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                // Standard battery optimization
                Intent().apply {
                    action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                // App info page
                Intent().apply {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )

            for (intent in intents) {
                try {
                    context.startActivity(intent)
                    Log.i("BatteryOpt", "Successfully opened Mobicel battery settings")
                    return
                } catch (e: Exception) {
                    Log.w("BatteryOpt", "Mobicel intent failed: ${intent.action}", e)
                    continue
                }
            }

            // Final fallback
            requestIgnoreBatteryOptimizations(context)
        } catch (e: Exception) {
            Log.e("BatteryOpt", "Mobicel optimization failed completely", e)
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
