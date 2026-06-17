package com.shiftsmart.plus.utils

import android.app.Dialog
import android.util.Log
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.LinkedBlockingQueue

/**
 * Manages dialog queuing to prevent multiple dialogs from showing simultaneously.
 * Dialogs are shown one at a time, with the next dialog appearing after the current one is dismissed.
 */
object DialogManager {
    private const val TAG = "DialogManager"

    // Track states
    private var isDialogShowing = false
    private var isLoadingDialogShowing = false
    private var isPermissionDialogShowing = false
    private var isAccessibilityDialogShowing = false
    private var isApiCallInProgress = false

    // ✅ Callback to check if progress dialog is showing (from MainActivity)
    private var progressDialogChecker: (() -> Boolean)? = null

    // Queue for pending dialogs
    private val dialogQueue = LinkedBlockingQueue<PendingDialog>()

    data class PendingDialog(
        val id: String,
        val type: DialogType,
        val action: () -> Dialog?
    )

    enum class DialogType {
        UPDATE,
        PERMISSION,
        BACKGROUND_LOCATION,
        FULL_SCREEN_INTENT,
        ACCESSIBILITY,
        LOADING,
        OTHER
    }

    /**
     * Register a callback to check if progress dialog is showing
     * This is called from MainActivity to provide real-time state
     */
    fun setProgressDialogChecker(checker: (() -> Boolean)?) {
        progressDialogChecker = checker
    }

    /**
     * Mark API call as in progress
     */
    fun setApiCallInProgress(inProgress: Boolean) {
        isApiCallInProgress = inProgress
        Log.i(TAG, if (inProgress) "📡 API call started" else "📡 API call completed")
    }

    /**
     * Check if API call is in progress
     */
    fun isApiCallInProgress(): Boolean {
        return isApiCallInProgress
    }

    /**
     * Check if any dialog is currently showing
     */
    fun isAnyDialogShowing(): Boolean {
        // ✅ Also check if progress dialog is showing via callback
        val isProgressDialogShowing = progressDialogChecker?.invoke() ?: false
        return isDialogShowing || isLoadingDialogShowing || isPermissionDialogShowing || isAccessibilityDialogShowing || isApiCallInProgress || isProgressDialogShowing
    }

    /**
     * Check if a specific type of dialog is showing
     */
    fun isDialogShowing(type: DialogType): Boolean {
        return when (type) {
            DialogType.LOADING -> isLoadingDialogShowing
            DialogType.PERMISSION -> isPermissionDialogShowing
            DialogType.ACCESSIBILITY -> isAccessibilityDialogShowing
            else -> isDialogShowing
        }
    }

    /**
     * Queue a dialog to be shown when the current dialog is dismissed.
     * If no dialog is showing, this will show immediately.
     */
    fun queueDialog(id: String, type: DialogType, action: () -> Dialog?): Boolean {
        Log.i(TAG, "📋 Attempting to queue dialog: $id (type: $type)")

        // If dialog is already showing, queue it
        if (isAnyDialogShowing()) {
            Log.i(TAG, "⏳ Dialog already showing. Queuing dialog: $id")
            dialogQueue.offer(PendingDialog(id, type, action))
            return false
        }

        // Otherwise show it immediately
        return showDialog(id, type, action)
    }

    /**
     * Show a dialog immediately
     */
    private fun showDialog(id: String, type: DialogType, action: () -> Dialog?): Boolean {
        val dialog = try {
            action()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating dialog: $id - ${e.message}", e)
            null
        } ?: return false

        isDialogShowing = true
        when (type) {
            DialogType.LOADING -> isLoadingDialogShowing = true
            DialogType.PERMISSION -> isPermissionDialogShowing = true
            DialogType.ACCESSIBILITY -> isAccessibilityDialogShowing = true
            else -> {}
        }

        Log.i(TAG, "✅ Showing dialog: $id (type: $type)")

        // Set dismiss listener to show next queued dialog
        if (dialog is AlertDialog) {
            dialog.setOnDismissListener {
                onDialogDismissed(id, type)
            }
        } else {
            // For generic Dialog objects
            dialog.setOnDismissListener {
                onDialogDismissed(id, type)
            }
        }

        try {
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing dialog: $id - ${e.message}", e)
            onDialogDismissed(id, type)
            return false
        }

        return true
    }

    /**
     * Called when a dialog is dismissed to show the next queued dialog
     */
    private fun onDialogDismissed(id: String, type: DialogType) {
        Log.i(TAG, "🔄 Dialog dismissed: $id")

        isDialogShowing = false
        when (type) {
            DialogType.LOADING -> isLoadingDialogShowing = false
            DialogType.PERMISSION -> isPermissionDialogShowing = false
            DialogType.ACCESSIBILITY -> isAccessibilityDialogShowing = false
            else -> {}
        }

        // Show next queued dialog if any
        val nextDialog = dialogQueue.poll()
        if (nextDialog != null) {
            Log.i(TAG, "📋 Processing queued dialog: ${nextDialog.id}")
            showDialog(nextDialog.id, nextDialog.type, nextDialog.action)
        }
    }

    /**
     * Clear all queued dialogs
     */
    fun clearQueue() {
        Log.i(TAG, "🗑️ Clearing dialog queue (${dialogQueue.size} dialogs)")
        dialogQueue.clear()
    }

    /**
     * Reset all dialog states (use with caution)
     */
    fun resetAll() {
        Log.i(TAG, "🔄 Resetting all dialog states")
        isDialogShowing = false
        isLoadingDialogShowing = false
        isPermissionDialogShowing = false
        isAccessibilityDialogShowing = false
        clearQueue()
    }
}

