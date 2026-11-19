package com.debashis.carlauncher

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * First-run firmware detection and interactive dock setup.
 *
 * Allows drivers to review auto-detected apps (Navigation, Music, Radio, Phone,
 * Video/YouTube, Settings, Aux, Mirroring). If an app is not automatically matched,
 * it is left empty and can be clicked to choose any installed app via [AppPickerActivity].
 */
class SetupActivity : Activity() {

    companion object {
        private const val REQ_PICK_ROLE = 201
    }

    private lateinit var summaryView: TextView
    private lateinit var content: LinearLayout
    private lateinit var result: VendorProfiles.Result
    private val currentAssignments = mutableMapOf<VendorProfiles.Role, String>()
    private var editingRole: VendorProfiles.Role? = null

    private val roleLabels = mapOf(
        VendorProfiles.Role.PHONE_MIRRORING to "Phone mirroring",
        VendorProfiles.Role.NAVIGATION to "Navigation / Maps",
        VendorProfiles.Role.MUSIC to "Music",
        VendorProfiles.Role.RADIO to "Radio",
        VendorProfiles.Role.PHONE to "Phone",
        VendorProfiles.Role.VIDEO to "Video / YouTube",
        VendorProfiles.Role.SETTINGS to "Settings",
        VendorProfiles.Role.AUX to "AUX / AV-IN",
        VendorProfiles.Role.BLUETOOTH to "Bluetooth"
    )

    private val allRoles = listOf(
        VendorProfiles.Role.PHONE_MIRRORING,
        VendorProfiles.Role.NAVIGATION,
        VendorProfiles.Role.MUSIC,
        VendorProfiles.Role.RADIO,
        VendorProfiles.Role.PHONE,
        VendorProfiles.Role.VIDEO,
        VendorProfiles.Role.SETTINGS,
        VendorProfiles.Role.AUX,
        VendorProfiles.Role.BLUETOOTH
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        result = try {
            VendorProfiles.detect(packageManager)
        } catch (e: Exception) {
            VendorProfiles.Result("Generic Android", 0, 0, emptyMap())
        }

        currentAssignments.clear()
        currentAssignments.putAll(result.resolved)

        setContentView(buildRoot())
        hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_ROLE && resultCode == RESULT_OK) {
            val role = editingRole ?: return
            val component = data?.getStringExtra(AppPickerActivity.EXTRA_COMPONENT).orEmpty()
            if (component.isEmpty() || component == Prefs.DRAWER) {
                currentAssignments.remove(role)
            } else {
                currentAssignments[role] = component
            }
            refreshRows()
        }
    }

    // ---------------------------------------------------------------- layout

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            setPadding(dp(32), dp(28), dp(32), dp(24))
        }

        val heading = TextView(this).apply {
            text = "Set up your dock"
            setTextColor(getColor(R.color.ink))
            textSize = 26f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        root.addView(heading)

        summaryView = TextView(this).apply {
            text = summaryLine()
            setTextColor(getColor(R.color.ink_soft))
            textSize = 15f
            setPadding(0, dp(6), 0, dp(18))
        }
        root.addView(summaryView)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.group_bg)
            clipToOutline = true
        }

        populateRows()
        scroll.addView(content)
        root.addView(scroll)

        root.addView(buildButtons())
        return root
    }

    private fun populateRows() {
        content.removeAllViews()
        for (role in allRoles) {
            content.addView(buildRoleRow(role))
        }
    }

    private fun refreshRows() {
        summaryView.text = summaryLine()
        populateRows()
    }

    private fun summaryLine(): String {
        val family = result.family
        val filled = currentAssignments.size
        val total = allRoles.size
        return if (filled == 0) {
            "$family firmware: tap any section below to assign an app"
        } else {
            "$family firmware detected, $filled of $total apps configured (tap to change)"
        }
    }

    private fun buildRoleRow(role: VendorProfiles.Role): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.row_bg)
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isClickable = true
            isFocusable = true
        }

        val component = currentAssignments[role]
        val icon = ImageView(this).apply {
            val size = dp(42)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(14)
            }
            setBackgroundResource(R.drawable.squircle_plate)
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
            setImageDrawable(resolveIcon(component))
        }
        row.addView(icon)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = roleLabels[role] ?: role.name
            setTextColor(getColor(R.color.ink))
            textSize = 16f
        }
        textCol.addView(title)

        val sub = TextView(this).apply {
            textSize = 13f
            if (!component.isNullOrEmpty()) {
                text = resolveAppLabel(component)
                setTextColor(getColor(R.color.ink_soft))
            } else {
                text = "Empty (Tap to choose app)"
                setTextColor(getColor(R.color.accent))
            }
        }
        textCol.addView(sub)
        row.addView(textCol)

        val value = TextView(this).apply {
            textSize = 13f
            if (!component.isNullOrEmpty()) {
                text = component.substringBefore('/')
                setTextColor(getColor(R.color.muted))
            } else {
                text = "Not set"
                setTextColor(getColor(R.color.accent))
            }
        }
        row.addView(value)

        row.setOnClickListener {
            editingRole = role
            startActivityForResult(
                Intent(this, AppPickerActivity::class.java)
                    .putExtra(AppPickerActivity.EXTRA_SLOT, role.ordinal),
                REQ_PICK_ROLE
            )
        }

        return row
    }

    private fun resolveIcon(component: String?): Drawable? {
        if (component.isNullOrEmpty()) return null
        val custom = HeadunitIcons.getCustomIcon(this, component)
        if (custom != null) return custom

        return try {
            val pkg = component.substringBefore('/')
            if (component.contains('/')) {
                val cn = ComponentName(pkg, component.substringAfter('/'))
                packageManager.getActivityInfo(cn, 0).loadIcon(packageManager)
            } else {
                packageManager.getApplicationIcon(pkg)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun resolveAppLabel(component: String): String {
        return try {
            val pkg = component.substringBefore('/')
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (t: Throwable) {
            component.substringBefore('/')
        }
    }

    private fun buildButtons(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        }

        val skip = TextView(this).apply {
            text = "Set up manually"
            setTextColor(getColor(R.color.ink_soft))
            textSize = 16f
            gravity = Gravity.CENTER
            minimumHeight = dp(64)
            setBackgroundResource(R.drawable.row_bg)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
            setOnClickListener { finish() }
        }
        row.addView(skip)

        val use = TextView(this).apply {
            text = "Use this"
            setTextColor(getColor(R.color.accent_ink))
            textSize = 16f
            gravity = Gravity.CENTER
            minimumHeight = dp(64)
            setBackgroundColor(getColor(R.color.accent))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                applyDock()
                finish()
            }
        }
        row.addView(use)

        return row
    }

    private fun applyDock() {
        val components = VendorProfiles.toDockComponents(currentAssignments)
        for (i in components.indices) {
            Prefs.setSlot(this, i, Prefs.Slot(components[i]))
        }
        Prefs.setSetupShown(this, true)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
