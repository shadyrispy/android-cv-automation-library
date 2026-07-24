package com.steve1316.automation_library.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.steve1316.automation_library.data.SharedData

/**
 * Permission guide entry point; provides one-stop authorization guidance.
 *
 * Recommended usage (in host Activity):
 * ```kotlin
 * companion object { private const val REQ_PERMISSIONS = 0xA001 }
 *
 * fun startAutomation() {
 *     if (!PermissionGuide.checkAll(this).allGranted()) {
 *         PermissionGuide.start(this, REQ_PERMISSIONS)
 *     } else {
 *         // Start the service directly if projection grant is cached, otherwise re-guide.
 *     }
 * }
 *
 * override fun onActivityResult(req: Int, res: Int, data: Intent?) {
 *     super.onActivityResult(req, res, data)
 *     if (req == REQ_PERMISSIONS && res == Activity.RESULT_OK && data != null) {
 *         val resultCode = data.getIntExtra(PermissionGuide.EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
 *         val resultData = data.getParcelableExtra<Intent>(PermissionGuide.EXTRA_RESULT_DATA)
 *         if (resultData != null) {
 *             val serviceIntent = MediaProjectionService.getStartIntent(this, resultCode, resultData)
 *             ContextCompat.startForegroundService(this, serviceIntent)
 *         }
 *     }
 * }
 * ```
 */
object PermissionGuide {
    private val tag = "${SharedData.loggerTag}PermissionGuide"

    /** Default request code. */
    const val DEFAULT_REQUEST_CODE = 0xA001

    /** Extra key returned to the host Activity: MediaProjection authorization resultCode. */
    const val EXTRA_RESULT_CODE = "PERMISSION_GUIDE_RESULT_CODE"

    /** Extra key returned to the host Activity: MediaProjection authorization Intent data. */
    const val EXTRA_RESULT_DATA = "PERMISSION_GUIDE_RESULT_DATA"

    /**
     * Start the permission guide page.
     *
     * On completion, the host's onActivityResult receives:
     * - [Activity.RESULT_OK]: user clicked "Start" (overlay + accessibility granted; projection Intent returned via extras).
     * - [Activity.RESULT_CANCELED]: user exited before completing all authorizations.
     *
     * @param hostActivity The host app's Activity.
     * @param requestCode Request code, returned in the host's onActivityResult.
     */
    fun start(hostActivity: Activity, requestCode: Int = DEFAULT_REQUEST_CODE) {
        val intent = Intent(hostActivity, PermissionGuideActivity::class.java)
        hostActivity.startActivityForResult(intent, requestCode)
    }

    /**
     * Check the current status of all required permissions.
     *
     * Note: MediaProjection is one-shot; [PermissionStatus.mediaProjection] always returns
     * [PermissionState.DENIED]. Actual projection state must be tracked by the guide page or the host.
     */
    fun checkAll(context: Context): PermissionStatus {
        return PermissionChecker.checkAll(context)
    }
}
