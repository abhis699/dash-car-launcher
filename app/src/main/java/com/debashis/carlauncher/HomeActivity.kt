package com.debashis.carlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.ViewStub
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.io.File
import kotlin.math.roundToInt

/**
 * Dash - home screen for the myTVS RK3326 head unit.
 *
 * Deliberately built on framework APIs only. No AppCompat, no Material, no Compose.
 * The device has 1.9 GB of RAM and a quad A35; every dependency would be paid for
 * at every boot, for a screen with eight buttons on it.
 */
class HomeActivity : Activity() {

    private companion object {
        const val REQ_LOCATION = 1
        const val REQ_PICK_SLOT = 101

        /**
         * A frozen speed is worse than no speed: it reads as real while being wrong.
         * If no GPS fix arrives for this long, fall back to a dash.
         */
        const val SPEED_STALE_MS = 5000L

        /** Below this, a GPS reading is standstill jitter rather than movement. 3 km/h. */
        const val DEADBAND_MS = 0.833f
        const val TICK_MS = 1000L
    }

    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var speedView: TextView
    private lateinit var btView: TextView
    private lateinit var gpsView: TextView
    private lateinit var npTitle: TextView
    private lateinit var npSub: TextView
    private lateinit var npArt: ImageView
    private lateinit var npPlay: ImageView
    private lateinit var npNext: ImageView

    /**
     * Media card views, so the same render path can drive either layout.
     * Drive mode is a second inflated hierarchy, not the same views moved around.
     */
    private class MediaCard(
        val root: View,
        val art: ImageView,
        val title: TextView,
        val sub: TextView,
        val play: ImageView,
        val next: ImageView
    )

    private var normalCard: MediaCard? = null
    private var driveCard: MediaCard? = null

    private var driveRoot: View? = null
    private var driveSpeed: TextView? = null
    private var driveSpeedUnit: TextView? = null
    private var driveClock: TextView? = null
    private var driveDock: LinearLayout? = null

    private var inDriveMode = false
    private var idleDimmed = false
    private var lastKmh = 0f

    private val handler = Handler(Looper.getMainLooper())
    private var lastFixAt = 0L
    private var satellites = 0
    private var locationManager: LocationManager? = null

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateClock()
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateBluetooth()
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            lastFixAt = SystemClock.elapsedRealtime()
            // getSpeed() is metres per second. Never negative, but clamp anyway.
            val ms = loc.speed.coerceAtLeast(0f)
            // GPS jitters at a standstill, so a parked car reads 1 or 2. The deadband is held
            // in m/s so it means the same thing in either unit.
            val display = if (ms < DEADBAND_MS) 0f else ms * unitFactor()
            speedView.text = display.roundToInt().toString()
            driveSpeed?.text = display.roundToInt().toString()

            lastKmh = if (ms < DEADBAND_MS) 0f else ms * 3.6f
            Trip.onLocation(this@HomeActivity, loc)
            renderTrip()
            updateDriveMode()
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun unitFactor() =
        if (Prefs.units(this) == Prefs.UNITS_MPH) 2.23694f else 3.6f

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satellites = used
            updateGpsLabel()
        }
    }

    /** Ticks once a second purely to expire a stale speed reading. */
    private val staleCheck = object : Runnable {
        override fun run() {
            if (lastFixAt != 0L && SystemClock.elapsedRealtime() - lastFixAt > SPEED_STALE_MS) {
                speedView.text = getString(R.string.no_speed)
            }
            updateGpsLabel()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layoutRes())
        hideSystemBars()

        clockView = findViewById(R.id.clock)
        dateView = findViewById(R.id.date)
        speedView = findViewById(R.id.speed)
        btView = findViewById(R.id.bt_status)
        gpsView = findViewById(R.id.gps_status)
        npTitle = findViewById(R.id.np_title)
        npSub = findViewById(R.id.np_sub)
        npArt = findViewById(R.id.np_art)
        npPlay = findViewById(R.id.np_play)
        npNext = findViewById(R.id.np_next)

        normalCard = MediaCard(
            findViewById(R.id.now_playing), npArt, npTitle, npSub, npPlay, npNext
        )

        Trip.load(this)
        findViewById<View>(R.id.trip_card).setOnLongClickListener { showTripMenu(true); true }
        findViewById<View>(R.id.trip_strip).setOnLongClickListener { showTripMenu(true); true }
        findViewById<View>(R.id.trip_menu).setOnClickListener { showTripMenu(false) }
        findViewById<View>(R.id.trip_menu_new).setOnClickListener {
            Trip.reset(this)
            showTripMenu(false)
            renderTrip()
        }
        findViewById<View>(R.id.trip_menu_hide).setOnClickListener {
            Trip.setCardEnabled(this, false)
            showTripMenu(false)
            renderTrip()
        }

        findViewById<View>(R.id.screen_off).setOnClickListener { setScreenOff(false) }
        clockView.setOnLongClickListener { setScreenOff(true); true }
        findViewById<View>(R.id.btn_settings)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        npPlay.setOnClickListener { MediaManager.playPause() }
        npNext.setOnClickListener { MediaManager.skipNext() }
        findViewById<View>(R.id.now_playing).setOnClickListener { openPlayingApp() }

        loadWallpaper()
        buildDock()
        updateClock()
        speedView.text = getString(R.string.no_speed)

        locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager
        ensureLocationPermission()
        loadIconPack()
        maybeOfferSetup()
    }

    /**
     * v2: read the chosen style from SharedPreferences and return the matching layout.
     * Kept as a single call site so adding activity_home_grid and activity_home_rail
     * is a two line change rather than a refactor.
     */
    private fun layoutRes(): Int = R.layout.activity_home

    // -------------------------------------------------------- wallpaper

    /**
     * Wallpaper file lives in the app's own external files dir:
     *   /sdcard/Android/data/com.debashis.carlauncher/files/wallpaper.jpg
     * That location needs NO storage permission, which is why it beats /sdcard/Pictures.
     * Replace the file and the next return to the home screen picks it up.
     */
    private fun loadWallpaper() {
        val view = findViewById<ImageView>(R.id.wallpaper) ?: return
        val extDir = getExternalFilesDir(null) ?: filesDir
        val file = File(extDir, "wallpaper.jpg")
        if (!file.exists()) {
            view.setImageDrawable(null)   // fall through to the system wallpaper
            return
        }

        try {
            // Measure first so a 12 megapixel phone photo costs the same as a correctly
            // sized one: decode straight down to roughly screen resolution.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)

            val target = resources.displayMetrics.widthPixels
            var sample = 1
            while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= target) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                // Halves the memory and is invisible on a dark photo behind a scrim.
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)?.let { view.setImageBitmap(it) }
        } catch (t: Throwable) {
            view.setImageDrawable(null)
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                // Swipe from an edge still brings them back temporarily, so the vendor
                // back and recents buttons are never permanently out of reach.
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

    // ---------------------------------------------------------------- dock

    private var editingSlot = -1

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_SLOT && resultCode == RESULT_OK) {
            val returnSlot = data?.getIntExtra(AppPickerActivity.EXTRA_SLOT, -1) ?: -1
            val targetSlot = if (returnSlot >= 0) returnSlot else editingSlot
            if (targetSlot >= 0) {
                val component = data?.getStringExtra(AppPickerActivity.EXTRA_COMPONENT) ?: return
                val label = data.getStringExtra(AppPickerActivity.EXTRA_LABEL).orEmpty()
                val currentSlot = Prefs.dock(this).getOrNull(targetSlot)
                val iconId = currentSlot?.iconId.orEmpty()
                Prefs.setSlot(this, targetSlot, Prefs.Slot(component, label, iconId))
                buildDock()
                editingSlot = -1
            }
        }
    }

    private fun buildDock() {
        val dock = findViewById<LinearLayout>(R.id.dock)
        dock.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for ((index, slot) in Prefs.dock(this).withIndex()) {
            // An empty slot still occupies its share of the row, so the remaining tiles do
            // not shuffle sideways every time one is cleared.
            val view = inflater.inflate(R.layout.dock_item, dock, false)
            val icon = view.findViewById<ImageView>(R.id.icon)
            val label = view.findViewById<TextView>(R.id.label)

            when {
                slot.component.isEmpty() -> {
                    label.text = ""
                    view.isClickable = false
                }

                slot.component == Prefs.DRAWER -> {
                    val custom = HeadunitIcons.getCustomIcon(this, Prefs.DRAWER, slot.iconId)
                    icon.setImageDrawable(custom ?: getDrawable(R.drawable.ic_apple_all_apps))
                    icon.background = null
                    icon.setPadding(0, 0, 0, 0)
                    label.text = if (slot.label.isNotBlank()) slot.label else getString(R.string.all_apps)
                    view.setOnClickListener {
                        startActivity(Intent(this, DrawerActivity::class.java))
                    }
                }

                else -> {
                    val resolved = resolve(slot)
                    if (resolved == null) {
                        // Package missing, disabled, or exposing no launchable activity.
                        // Dim it rather than crashing or lying about what is there.
                        label.text = if (slot.label.isNotBlank()) slot.label else ""
                        view.alpha = 0.35f
                        view.isClickable = false
                    } else {
                        icon.setImageDrawable(resolved.first)
                        label.text = if (slot.label.isNotBlank()) slot.label
                        else appLabel(slot.component.substringBefore('/'))
                        view.setOnClickListener { startActivity(resolved.second) }
                    }
                }
            }

            // Long press an app slot directly opens options to change app or change icon.
            // Long press on the drawer slot opens Settings.
            view.setOnLongClickListener {
                if (slot.component == Prefs.DRAWER) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                } else {
                    showSlotOptionsDialog(index, slot)
                }
                true
            }
            dock.addView(view)
        }
    }

    private fun showSlotOptionsDialog(index: Int, slot: Prefs.Slot) {
        val appName = if (slot.label.isNotBlank()) slot.label
        else if (slot.component.isNotEmpty()) appLabel(slot.component.substringBefore('/'))
        else "Empty"
        val title = "Slot ${index + 1} ($appName)"

        val options = arrayOf("Change App", "Change Icon", "Clear Slot")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        editingSlot = index
                        startActivityForResult(
                            Intent(this, AppPickerActivity::class.java)
                                .putExtra(AppPickerActivity.EXTRA_SLOT, index),
                            REQ_PICK_SLOT
                        )
                    }
                    1 -> showSlotIconPicker(index)
                    2 -> {
                        Prefs.setSlot(this, index, Prefs.Slot(""))
                        buildDock()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSlotIconPicker(index: Int) {
        val iconOptions = mutableListOf<Pair<String, String>>()
        iconOptions.add("default" to "Default / Original App Icon")
        for (entry in HeadunitIcons.ALL_ICONS) {
            iconOptions.add(entry.id to entry.label)
        }

        val names = iconOptions.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Icon for Slot ${index + 1}")
            .setItems(names) { _, which ->
                val (id, label) = iconOptions[which]
                Prefs.setSlotIcon(this, index, id)
                buildDock()
                Toast.makeText(this, "Slot ${index + 1} icon set to $label", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Returns the icon and the intent to launch, or null if the slot is not usable. */
    private fun resolve(slot: Prefs.Slot): Pair<Drawable, Intent>? {
        val component = slot.component
        val pkg = component.substringBefore('/')
        return try {
            if (component.contains('/')) {
                val cn = ComponentName(pkg, component.substringAfter('/'))
                val activityInfo = packageManager.getActivityInfo(cn, 0)
                if (!activityInfo.enabled) return null
                val intent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val themed = IconPacks.drawableFor(component)
                    ?: HeadunitIcons.getCustomIcon(this, component, slot.iconId)
                (themed ?: activityInfo.loadIcon(packageManager)) to intent
            } else {
                val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return null
                val themed = intent.component?.flattenToShortString()?.let {
                    IconPacks.drawableFor(it) ?: HeadunitIcons.getCustomIcon(this, it, slot.iconId)
                } ?: HeadunitIcons.getCustomIcon(this, pkg, slot.iconId)
                (themed ?: packageManager.getApplicationIcon(pkg)) to intent
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()


    // ------------------------------------------------------------ clock

    private fun updateClock() {
        // Settings can override; the default still follows the unit's own setting.
        val use24 = when (Prefs.clockFormat(this)) {
            12 -> false
            24 -> true
            else -> android.text.format.DateFormat.is24HourFormat(this)
        }
        val pattern = if (use24) "H:mm" else "h:mm"
        val now = Date()
        val time = SimpleDateFormat(pattern, Locale.getDefault()).format(now)
        clockView.text = time
        driveClock?.text = time
        findViewById<TextView>(R.id.screen_off_clock)?.text = time
        dateView.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
        // Cheap enough to re-evaluate on the minute tick: auto night has to change at sunset
        // even if the car has not moved.
        applyDim()
    }

    // --------------------------------------------------------- first run

    /**
     * Offered once, on a unit whose dock has never been configured. Gated on "was it shown"
     * rather than "does a dock exist", because "Set up manually" deliberately writes nothing
     * and would otherwise be asked again on every boot.
     */
    private fun maybeOfferSetup() {
        if (Prefs.setupShown(this)) return
        if (Prefs.prefs(this).contains(Prefs.KEY_DOCK)) {
            Prefs.setSetupShown(this, true)
            return
        }
        Prefs.setSetupShown(this, true)
        try {
            startActivity(Intent(this, SetupActivity::class.java))
        } catch (t: Throwable) {
            // Setup activity could not be launched; fall back to default dock.
        }
    }

    // -------------------------------------------------------- icon pack

    /**
     * Parsing a pack means file I/O against another app's APK, so it cannot run on the main
     * thread. The dock is rebuilt once it lands; until then icons are the apps' own, which is
     * a correct screen rather than an empty one.
     */
    private fun loadIconPack() {
        val pack = Prefs.iconPack(this)
        if (pack.isEmpty()) {
            IconPacks.clear()
            return
        }
        Thread {
            IconPacks.load(packageManager, pack)
            runOnUiThread {
                if (!isFinishing) {
                    buildDock()
                    if (inDriveMode) buildDriveDock()
                }
            }
        }.start()
    }

    // -------------------------------------------------------------- trip

    /**
     * The trip card occupies the media card's place rather than sitting beside it. Two cards
     * competing for the same corner is how a glanceable screen stops being glanceable.
     */
    private fun renderTrip() {
        val card = findViewById<View>(R.id.trip_card) ?: return
        val mediaState = MediaManager.getCurrentState()
        val mediaShowing = mediaState.hasActiveSession && (mediaState.isPlaying || mediaState.title != null)

        val strip = findViewById<TextView>(R.id.trip_strip)
        val haveTrip = Trip.cardEnabled(this) && Trip.hasTrip() && !inDriveMode

        // Media wins the card slot, because what is playing changes and a trip total does
        // not. The trip drops to a single line underneath rather than disappearing: on a
        // long drive there is always music playing, and hiding the trip behind it would mean
        // never seeing the number the feature exists to show.
        val showCard = haveTrip && !mediaShowing
        val showStrip = haveTrip && mediaShowing

        card.visibility = if (showCard) View.VISIBLE else View.GONE
        strip.visibility = if (showStrip) View.VISIBLE else View.GONE
        normalCard?.root?.visibility = if (showCard) View.GONE else View.VISIBLE
        if (!haveTrip) return

        val mph = Prefs.units(this) == Prefs.UNITS_MPH
        val distance = if (mph) Trip.distanceKm() * 0.621371 else Trip.distanceKm()
        val avg = Trip.averageMs() * if (mph) 2.23694f else 3.6f
        val max = Trip.maxSpeedMs() * if (mph) 2.23694f else 3.6f

        findViewById<TextView>(R.id.trip_label)
            .setText(if (Trip.isActive()) R.string.this_trip else R.string.last_trip)
        findViewById<TextView>(R.id.trip_distance).text =
            if (distance < 100) String.format(Locale.getDefault(), "%.1f", distance)
            else distance.roundToInt().toString()
        findViewById<TextView>(R.id.trip_distance_unit).setText(if (mph) R.string.mi else R.string.km)
        findViewById<TextView>(R.id.trip_avg).text = avg.roundToInt().toString()
        findViewById<TextView>(R.id.trip_avg_unit).text = if (mph) "avg mph" else getString(R.string.avg_kmh)
        findViewById<TextView>(R.id.trip_max).text = max.roundToInt().toString()
        findViewById<TextView>(R.id.trip_max_unit).text = if (mph) "max mph" else getString(R.string.max_kmh)

        val minutes = Trip.movingMinutes()
        val movingText =
            if (minutes >= 60) "${minutes / 60}h${String.format(Locale.getDefault(), "%02d", minutes % 60)}"
            else "$minutes min"
        findViewById<TextView>(R.id.trip_time).text =
            if (minutes >= 60) "${minutes / 60}h${String.format(Locale.getDefault(), "%02d", minutes % 60)}"
            else minutes.toString()

        val distanceText =
            if (distance < 100) String.format(Locale.getDefault(), "%.1f", distance)
            else distance.roundToInt().toString()
        val distUnit = if (mph) "mi" else "km"
        val speedUnit = if (mph) "mph" else "km/h"
        strip.text = "$distanceText $distUnit  ·  ${avg.roundToInt()} avg $speedUnit  ·  $movingText"
    }

    private fun showTripMenu(show: Boolean) {
        val menu = findViewById<View>(R.id.trip_menu) ?: return
        if (show) {
            val elapsed = Trip.elapsedMinutes()
            val elapsedText =
                if (elapsed >= 60) "${elapsed / 60} h ${String.format(Locale.getDefault(), "%02d", elapsed % 60)} elapsed"
                else "$elapsed min elapsed"
            val stops = Trip.stopCount()
            findViewById<TextView>(R.id.trip_menu_info).text =
                "$elapsedText, ${stops} stop" + if (stops == 1) "" else "s"
            menu.visibility = View.VISIBLE
            menu.bringToFront()
        } else {
            menu.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------- drive

    /**
     * Two thresholds, never one. A single value flips the whole interface back and forth
     * every time the speed hovers around it, which in stop-start traffic is constant.
     */
    private fun updateDriveMode() {
        if (!Prefs.driveEnabled(this)) {
            if (inDriveMode) leaveDriveMode()
            return
        }
        if (!inDriveMode && lastKmh >= Prefs.driveOn(this)) enterDriveMode()
        else if (inDriveMode && lastKmh <= Prefs.driveOff(this)) leaveDriveMode()
    }

    private fun ensureDriveInflated() {
        if (driveRoot != null) return
        val stub = findViewById<ViewStub>(R.id.drive_stub) ?: return
        val root = stub.inflate()
        driveRoot = root
        driveSpeed = root.findViewById(R.id.drive_speed)
        driveSpeedUnit = root.findViewById(R.id.drive_speed_unit)
        driveClock = root.findViewById(R.id.drive_clock)
        driveDock = root.findViewById(R.id.drive_dock)

        driveCard = MediaCard(
            root.findViewById(R.id.drive_now_playing),
            root.findViewById(R.id.drive_np_art),
            root.findViewById(R.id.drive_np_title),
            root.findViewById(R.id.drive_np_sub),
            root.findViewById(R.id.drive_np_play),
            root.findViewById(R.id.drive_np_next)
        )
        driveCard?.play?.setOnClickListener { togglePlayPause() }
        driveCard?.next?.setOnClickListener { MediaManager.skipNext() }
        driveCard?.root?.setOnClickListener { openPlayingApp() }
        driveClock?.setOnLongClickListener { setScreenOff(true); true }
    }

    private fun enterDriveMode() {
        ensureDriveInflated()
        inDriveMode = true
        buildDriveDock()
        driveSpeedUnit?.text = unitLabel()
        findViewById<View>(R.id.normal_root).visibility = View.GONE
        driveRoot?.visibility = View.VISIBLE
        updateClock()
        renderMedia()
    }

    private fun leaveDriveMode() {
        inDriveMode = false
        driveRoot?.visibility = View.GONE
        findViewById<View>(R.id.normal_root).visibility = View.VISIBLE
        updateClock()
        renderMedia()
        renderTrip()
    }

    private fun buildDriveDock() {
        val dock = driveDock ?: return
        dock.removeAllViews()
        val inflater = LayoutInflater.from(this)

        val slots = Prefs.driveDock(this) + Prefs.Slot(Prefs.DRAWER, getString(R.string.all_apps))
        for (slot in slots) {
            val view = inflater.inflate(R.layout.drive_tile, dock, false)
            val icon = view.findViewById<ImageView>(R.id.icon)
            val label = view.findViewById<TextView>(R.id.label)

            when {
                slot.component == Prefs.DRAWER -> {
                    val custom = HeadunitIcons.getCustomIcon(this, Prefs.DRAWER, slot.iconId)
                    icon.setImageDrawable(custom ?: getDrawable(R.drawable.ic_apple_all_apps))
                    label.text = slot.label
                    view.setOnClickListener {
                        startActivity(Intent(this, DrawerActivity::class.java))
                    }
                }
                slot.component.isEmpty() -> {
                    label.text = ""
                    view.isClickable = false
                }
                else -> {
                    val resolved = resolve(slot)
                    if (resolved == null) {
                        view.alpha = 0.35f
                        view.isClickable = false
                        label.text = slot.label
                    } else {
                        icon.setImageDrawable(resolved.first)
                        label.text = if (slot.label.isNotBlank()) slot.label
                        else appLabel(slot.component.substringBefore('/'))
                        view.setOnClickListener { startActivity(resolved.second) }
                    }
                }
            }
            dock.addView(view)
        }
    }

    // ------------------------------------------------------------- night

    /**
     * Two reasons the screen dims, combined into one black overlay at the higher of the two
     * levels. One layer, not per-element alpha: dimming each view would mean touching every
     * one of them and would still leave the wallpaper at full brightness.
     *
     * 1. NIGHT. A clock comparison. An earlier version computed sunrise and sunset from the
     *    GPS fix, which was cleverness for its own sake: it needed a location before it could
     *    decide anything and gave a worse answer than "is it evening yet".
     * 2. IDLE. Nothing touched for a few minutes while the home screen is showing. A map or a
     *    video is a different app in the foreground, so this activity is stopped and the timer
     *    is not running: the "do not dim during navigation" case handles itself.
     */
    private fun applyDim() {
        val dim = findViewById<View>(R.id.night_dim) ?: return

        val nightFraction = if (isNightNow()) Prefs.nightLevel(this) / 100f else 0f
        val idleFraction = if (idleDimmed) Prefs.IDLE_LEVEL / 100f else 0f
        val level = maxOf(nightFraction, idleFraction)

        dim.visibility = if (level > 0f) View.VISIBLE else View.GONE
        dim.alpha = level
    }

    private fun isNightNow(): Boolean = when (Prefs.nightMode(this)) {
        Prefs.NIGHT_ON -> true
        Prefs.NIGHT_OFF -> false
        else -> {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val start = Prefs.nightStart(this)
            val end = Prefs.nightEnd(this)
            // Night wraps midnight, so "after 19 or before 6" rather than a simple range.
            if (start > end) hour >= start || hour < end else hour in start until end
        }
    }

    // -------------------------------------------------------------- idle

    private val idleTimeout = Runnable {
        if (Prefs.idleDimEnabled(this)) {
            idleDimmed = true
            applyDim()
        }
    }

    private fun restartIdleTimer() {
        handler.removeCallbacks(idleTimeout)
        if (!Prefs.idleDimEnabled(this)) return
        handler.postDelayed(idleTimeout, Prefs.idleMinutes(this) * 60_000L)
    }

    /**
     * Fires for any touch or key anywhere in this activity, so no view needs its own listener
     * and a tap that also presses a tile still counts as activity.
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (idleDimmed) {
            idleDimmed = false
            applyDim()
        }
        restartIdleTimer()
    }

    // --------------------------------------------------------- screen off
    // --------------------------------------------------------- screen off

    private fun setScreenOff(off: Boolean) {
        val view = findViewById<View>(R.id.screen_off) ?: return
        if (off) {
            findViewById<TextView>(R.id.screen_off_clock).text = clockView.text
            view.visibility = View.VISIBLE
            view.bringToFront()
        } else {
            view.visibility = View.GONE
        }
    }

    private fun togglePlayPause() {
        MediaManager.playPause()
    }

    private fun openPlayingApp() {
        if (!MediaManager.hasNotificationAccess(this)) {
            showMediaPermissionDialog()
        } else {
            MediaManager.launchMediaApp(this)
        }
    }

    private fun showMediaPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Media Playback Access")
            .setMessage("Allow notification access so Dash Car Launcher can capture music and media playback from Spotify, YouTube, and other apps.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun unitLabel() = if (Prefs.units(this) == Prefs.UNITS_MPH) "mph" else getString(R.string.kmh)

    // ------------------------------------------------------------ media

    private val mediaListener = object : MediaManager.Listener {
        override fun onMediaChanged(state: MediaManager.MediaState) {
            renderMedia(state)
        }
    }

    /** Every inflated card, so switching layouts never shows stale media. */
    private fun cards() = listOfNotNull(normalCard, driveCard)

    private fun renderMedia(state: MediaManager.MediaState = MediaManager.getCurrentState()) {
        val hasMedia = state.hasActiveSession && (state.title != null || state.isPlaying)

        if (!hasMedia) {
            for (card in cards()) {
                card.play.visibility = View.GONE
                card.next.visibility = View.GONE
                card.art.setImageDrawable(null)
            }
            updateBluetooth()
            renderTrip()
            return
        }

        val shownTitle = state.title ?: getString(R.string.nothing_playing)
        val shownSub = state.artist
            ?: state.album
            ?: if (state.packageName != null) appLabel(state.packageName) else "Playing"

        val playing = state.isPlaying
        val canSkip = state.canSkip

        for (card in cards()) {
            card.title.text = shownTitle
            card.sub.text = shownSub
            card.art.setImageBitmap(state.art)
            card.play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            card.play.visibility = View.VISIBLE
            card.next.visibility = if (canSkip) View.VISIBLE else View.GONE
        }
        renderTrip()
    }

    private fun usable(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.equals("unknown", true) || trimmed == "<unknown>") null else trimmed
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Throwable) {
        pkg
    }

    // -------------------------------------------------------- bluetooth

    @SuppressLint("MissingPermission")
    private fun updateBluetooth() {
        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (t: Throwable) {
            null
        }
        val connected = try {
            adapter != null &&
                    adapter.isEnabled &&
                    adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothAdapter.STATE_CONNECTED
        } catch (t: Throwable) {
            false
        }

        btView.text = when {
            adapter == null || !adapter.isEnabled -> "Bluetooth off"
            connected -> "Bluetooth connected"
            else -> "Bluetooth on"
        }

        // Only owns the card when no media session is active.
        if (MediaManager.getCurrentState().hasActiveSession) return
        val title = if (connected) "Bluetooth audio" else getString(R.string.nothing_playing)
        val sub = if (connected) getString(R.string.bt_connected_sub)
        else if (!MediaManager.hasNotificationAccess(this)) "Tap to allow media access"
        else getString(R.string.nothing_playing_sub)
        for (card in cards()) {
            card.title.text = title
            card.sub.text = sub
        }
    }

    // -------------------------------------------------------------- gps

    private fun updateGpsLabel() {
        gpsView.text = when {
            !hasLocationPermission() -> "Location off"
            lastFixAt == 0L -> "Waiting for GPS"
            satellites > 0 -> "$satellites satellites"
            else -> "GPS"
        }
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun ensureLocationPermission() {
        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_LOCATION) startLocation()
    }

    private fun startLocation() {
        if (!hasLocationPermission()) return
        val lm = locationManager ?: return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
        } catch (e: Throwable) {
            // Permission revoked or GPS provider unavailable. Leave the dash showing.
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.registerGnssStatusCallback(mainExecutor, gnssCallback)
            } else {
                @Suppress("DEPRECATION")
                lm.registerGnssStatusCallback(gnssCallback, handler)
            }
        } catch (e: Throwable) {
            // GNSS status reporting not supported on this device/ROM.
        }
    }

    private fun stopLocation() {
        val lm = locationManager ?: return
        try {
            lm.removeUpdates(locationListener)
        } catch (e: Throwable) {
            // Nothing to unwind.
        }
        try {
            lm.unregisterGnssStatusCallback(gnssCallback)
        } catch (e: Throwable) {
            // Nothing to unwind.
        }
    }

    // ------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)      // fires once a minute, no polling
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        registerReceiver(btReceiver, IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        })
        updateClock()
        updateBluetooth()
        renderMedia()
        startLocation()
        MediaManager.init(this)
        MediaManager.addListener(mediaListener)
        handler.post(staleCheck)
        restartIdleTimer()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(timeReceiver)
        unregisterReceiver(btReceiver)
        stopLocation()
        MediaManager.removeListener(mediaListener)
        Trip.save(this)
        handler.removeCallbacks(staleCheck)
        handler.removeCallbacks(idleTimeout)
    }

    override fun onResume() {
        super.onResume()
        // A package may have been enabled, disabled or installed while we were away,
        // and the wallpaper file may have been replaced.
        loadWallpaper()
        loadIconPack()
        buildDock()
        findViewById<TextView>(R.id.speed_unit).text = unitLabel()
        driveSpeedUnit?.text = unitLabel()
        if (inDriveMode) buildDriveDock()
        updateDriveMode()
        renderTrip()
        applyDim()
        updateClock()
        updateBluetooth()
        renderMedia()
    }

    /** Home is the bottom of the stack. Back must not leave the user on a blank screen. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Intentionally empty.
    }
}
