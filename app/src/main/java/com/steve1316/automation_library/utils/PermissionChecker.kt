package com.steve1316.automation_library.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.steve1316.automation_library.data.SharedData

/**
 * Step identifiers for the 3-step permission guide; order matches the cards top-to-bottom.
 */
enum class PermissionStep(val order: Int) {
    OVERLAY(0),
    ACCESSIBILITY(1),
    MEDIA_PROJECTION(2),
}

/**
 * Current state of a single permission.
 */
enum class PermissionState {
    GRANTED,
    DENIED,
    REQUESTING,
}

/**
 * Aggregate status of all required permissions.
 *
 * Note: [mediaProjection] is a one-shot grant; it only means the current session has the
 * resultCode/data. A process restart requires re-running createScreenCaptureIntent.
 */
data class PermissionStatus(
    val overlay: PermissionState,
    val accessibility: PermissionState,
    val mediaProjection: PermissionState,
) {
    /** Whether overlay + accessibility are both granted (projection is one-shot, not part of persistent check). */
    fun allGranted(): Boolean =
        overlay == PermissionState.GRANTED && accessibility == PermissionState.GRANTED

    /** Returns the list of not-yet-granted steps. */
    fun missing(): List<PermissionStep> = buildList {
        if (overlay == PermissionState.DENIED) add(PermissionStep.OVERLAY)
        if (accessibility == PermissionState.DENIED) add(PermissionStep.ACCESSIBILITY)
        if (mediaProjection == PermissionState.DENIED) add(PermissionStep.MEDIA_PROJECTION)
    }
}

/**
 * Permission detection and intent utility.
 *
 * Provides detection and system-settings navigation for the three special permissions:
 * overlay, accessibility service, and screen projection. Detection methods are non-blocking
 * and safe to call on the main thread.
 */
object PermissionChecker {
    private val tag = "${SharedData.loggerTag}PermissionChecker"

    /**
     * Check whether the overlay permission (SYSTEM_ALERT_WINDOW) is granted.
     *
     * Android < 6.0 grants it by default; returns true.
     */
    fun isOverlayGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Check whether the specified accessibility service is enabled in system settings.
     *
     * Uses [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] string matching, compatible with both
     * the standard ":" separator and EMUI's ";".
     *
     * @param context Application context.
     * @param serviceClass Class of the accessibility service; defaults to [MyAccessibilityService].
     */
    fun isAccessibilityServiceEnabled(
        context: Context,
        serviceClass: Class<*> = MyAccessibilityService::class.java,
    ): Boolean {
        val expectedComponent = ComponentName(context, serviceClass).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        // Standard separator is ":"; some EMUI versions use ";".
        val services = enabledServices.split(":", ";")
        val enabled = services.any { it.equals(expectedComponent, ignoreCase = true) }

        if (!enabled) {
            // Fallback: check the ACCESSIBILITY_ENABLED master switch.
            val accessibilityEnabled = try {
                Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0,
                ) == 1
            } catch (e: Settings.SettingNotFoundException) {
                false
            }
            return accessibilityEnabled && services.any {
                it.contains(serviceClass.simpleName, ignoreCase = true)
            }
        }
        return true
    }

    /**
     * Create an intent to navigate to the overlay-permission management page.
     *
     * On some MIUI versions ACTION_MANAGE_OVERLAY_PERMISSION may not resolve; falls back to the
     * app-details page in that case.
     *
     * Caller should launch via startActivityForResult or plain startActivity.
     */
    fun createOverlayPermissionIntent(context: Context): Intent {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        // Fallback: some OEMs (e.g. MIUI) don't respond to ACTION_MANAGE_OVERLAY_PERMISSION.
        if (intent.resolveActivity(context.packageManager) == null) {
            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            intent.data = Uri.parse("package:${context.packageName}")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /**
     * Create an intent to navigate to the system accessibility settings page.
     *
     * The system cannot jump directly to a specific service; the user must find and enable it in the list.
     */
    fun createAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Check the current status of all required permissions.
     *
     * [mediaProjection] status is tracked by [PermissionGuideActivity] via a cache; here it defaults
     * to DENIED and the caller must track the actual state.
     */
    fun checkAll(context: Context): PermissionStatus {
        val overlayState = if (isOverlayGranted(context)) PermissionState.GRANTED else PermissionState.DENIED
        val accessibilityState = if (isAccessibilityServiceEnabled(context)) PermissionState.GRANTED else PermissionState.DENIED
        // Projection is one-shot and cannot be queried via system API; default DENIED, tracked by the guide page.
        val mediaProjectionState = PermissionState.DENIED
        return PermissionStatus(overlayState, accessibilityState, mediaProjectionState)
    }
}
