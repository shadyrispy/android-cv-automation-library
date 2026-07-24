package com.steve1316.automation_library.utils

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.steve1316.automation_library.R
import com.steve1316.automation_library.data.SharedData

/**
 * Permission guide Activity: single-page multi-card UI for the "overlay → accessibility → projection"
 * three-step authorization flow.
 *
 * The library manifest declares this Activity; manifest-merger auto-registers it into the host app.
 * Hosts launch it via [PermissionGuide.start] and receive results in onActivityResult.
 *
 * The three steps have no real dependencies; the user may click them in any order (default focus on the first card).
 * After returning from overlay/accessibility system settings, [onResume] re-checks status.
 * Projection is obtained via [ActivityResultContracts.StartActivityForResult].
 */
class PermissionGuideActivity : ComponentActivity() {
    companion object {
        private val tag = "${SharedData.loggerTag}PermissionGuideActivity"
    }

    // Cached projection authorization result.
    private var cachedResultCode: Int = Activity.RESULT_CANCELED
    private var cachedResultData: Intent? = null

    // UI references.
    private lateinit var progressText: TextView
    private lateinit var startButton: Button
    private val stepHolders = mutableMapOf<PermissionStep, StepViewHolder>()

    // MediaProjection authorization launcher (androidx.activity API).
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            cachedResultCode = result.resultCode
            cachedResultData = result.data
        }
        refreshAllSteps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.permission_guide_activity)

        progressText = findViewById(R.id.permission_guide_progress)
        startButton = findViewById(R.id.permission_start_button)
        startButton.setOnClickListener {
            // Return the projection authorization result to the host via Intent extras.
            val resultIntent = Intent().apply {
                putExtra(PermissionGuide.EXTRA_RESULT_CODE, cachedResultCode)
                putExtra(PermissionGuide.EXTRA_RESULT_DATA, cachedResultData)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }

        val container = findViewById<LinearLayout>(R.id.permission_steps_container)
        inflateStepCard(container, PermissionStep.OVERLAY, R.string.permission_step_overlay_title, R.string.permission_step_overlay_desc)
        inflateStepCard(container, PermissionStep.ACCESSIBILITY, R.string.permission_step_accessibility_title, R.string.permission_step_accessibility_desc)
        inflateStepCard(container, PermissionStep.MEDIA_PROJECTION, R.string.permission_step_projection_title, R.string.permission_step_projection_desc)
    }

    override fun onResume() {
        super.onResume()
        // Re-check overlay and accessibility status when returning from system settings.
        refreshAllSteps()
    }

    /**
     * Dynamically inflate a card and register its button click.
     */
    private fun inflateStepCard(
        container: LinearLayout,
        step: PermissionStep,
        titleRes: Int,
        descRes: Int,
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.permission_guide_step_item, container, false)
        val holder = StepViewHolder(
            rootView = view,
            iconView = view.findViewById(R.id.permission_step_icon),
            titleView = view.findViewById(R.id.permission_step_title),
            descView = view.findViewById(R.id.permission_step_desc),
            statusView = view.findViewById(R.id.permission_step_status),
            buttonView = view.findViewById(R.id.permission_step_button),
        )
        holder.titleView.setText(titleRes)
        holder.descView.setText(descRes)
        holder.buttonView.setOnClickListener { onStepButtonClick(step) }
        container.addView(view)
        stepHolders[step] = holder
    }

    /**
     * "Grant" button click handler: jump to the corresponding system settings or authorization dialog.
     */
    private fun onStepButtonClick(step: PermissionStep) {
        when (step) {
            PermissionStep.OVERLAY -> {
                startActivity(PermissionChecker.createOverlayPermissionIntent(this))
            }
            PermissionStep.ACCESSIBILITY -> {
                startActivity(PermissionChecker.createAccessibilitySettingsIntent())
            }
            PermissionStep.MEDIA_PROJECTION -> {
                val intent = MediaProjectionService.getScreenCaptureIntent(this)
                mediaProjectionLauncher.launch(intent)
            }
        }
    }

    /**
     * Refresh all card states, the progress text, and the "Start" button.
     */
    private fun refreshAllSteps() {
        val overlayGranted = PermissionChecker.isOverlayGranted(this)
        val accessibilityGranted = PermissionChecker.isAccessibilityServiceEnabled(this)
        val projectionGranted = cachedResultCode == Activity.RESULT_OK && cachedResultData != null

        updateStepCard(PermissionStep.OVERLAY, overlayGranted)
        updateStepCard(PermissionStep.ACCESSIBILITY, accessibilityGranted)
        updateStepCard(PermissionStep.MEDIA_PROJECTION, projectionGranted)

        // Progress text.
        val grantedCount = listOf(overlayGranted, accessibilityGranted, projectionGranted).count { it }
        progressText.text = getString(R.string.permission_guide_progress, grantedCount, 3)

        // "Start" button: enabled only after all permissions are granted.
        startButton.isEnabled = overlayGranted && accessibilityGranted && projectionGranted
    }

    /**
     * Update a single card's icon, status text, and button text.
     */
    private fun updateStepCard(step: PermissionStep, granted: Boolean) {
        val holder = stepHolders[step] ?: return
        if (granted) {
            holder.iconView.setImageResource(R.drawable.permission_step_granted)
            holder.statusView.text = getString(R.string.permission_status_granted)
            holder.statusView.setTextColor(0xFF4CAF50.toInt())
            holder.buttonView.text = getString(R.string.permission_status_granted)
            holder.buttonView.isEnabled = false
        } else {
            holder.iconView.setImageResource(R.drawable.permission_step_denied)
            holder.statusView.text = getString(R.string.permission_status_denied)
            holder.statusView.setTextColor(0xFFFF9800.toInt())
            holder.buttonView.text = getString(R.string.permission_action_grant)
            holder.buttonView.isEnabled = true
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Show a confirmation dialog if any permission is not yet granted.
        val overlayGranted = PermissionChecker.isOverlayGranted(this)
        val accessibilityGranted = PermissionChecker.isAccessibilityServiceEnabled(this)
        val projectionGranted = cachedResultCode == Activity.RESULT_OK && cachedResultData != null

        if (!overlayGranted || !accessibilityGranted || !projectionGranted) {
            AlertDialog.Builder(this)
                .setTitle(R.string.permission_exit_confirm_title)
                .setMessage(R.string.permission_exit_confirm_message)
                .setPositiveButton(R.string.permission_exit_confirm_yes) { _, _ ->
                    setResult(Activity.RESULT_CANCELED)
                    @Suppress("DEPRECATION")
                    super.onBackPressed()
                }
                .setNegativeButton(R.string.permission_exit_confirm_no, null)
                .show()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /** View references for a single card. */
    private data class StepViewHolder(
        val rootView: View,
        val iconView: ImageView,
        val titleView: TextView,
        val descView: TextView,
        val statusView: TextView,
        val buttonView: Button,
    )
}
