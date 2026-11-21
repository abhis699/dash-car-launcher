package com.debashis.carlauncher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * Built-in custom Apple-style squircle icons tailored to car dashboards and Android system apps.
 *
 * Full-bleed squircle icons matching CarPlay and modern iOS aesthetics:
 * - Downloaded plain vector icons from simpleicons.org and Phosphor
 * - Fixed background colors and gradients matching design/apple-2026-09-14.html
 * - Proportional symbol sizing inside squircle (~52% tile size)
 * - Automatic dynamic squircle background plate wrapping for any uncovered third-party app
 */
object HeadunitIcons {

    data class IconEntry(val id: String, val label: String, val drawableRes: Int)

    val ALL_ICONS = listOf(
        IconEntry("maps", "Navigation (Apple Maps)", R.drawable.ic_apple_maps),
        IconEntry("google_maps", "Google Maps", R.drawable.ic_apple_google_maps),
        IconEntry("waze", "Waze", R.drawable.ic_apple_waze),
        IconEntry("youtube", "YouTube", R.drawable.ic_apple_youtube),
        IconEntry("netflix", "Netflix", R.drawable.ic_apple_netflix),
        IconEntry("spotify", "Spotify", R.drawable.ic_apple_spotify),
        IconEntry("music", "Music (Apple Music)", R.drawable.ic_apple_music),
        IconEntry("radio", "FM / AM Radio", R.drawable.ic_apple_radio),
        IconEntry("aux", "AUX / AV-IN", R.drawable.ic_apple_aux),
        IconEntry("carplay", "CarPlay", R.drawable.ic_apple_carplay),
        IconEntry("android_auto", "Android Auto", R.drawable.ic_apple_android_auto),
        IconEntry("phone", "Phone / Contacts", R.drawable.ic_apple_phone),
        IconEntry("bluetooth", "Bluetooth", R.drawable.ic_apple_bluetooth),
        IconEntry("video", "Video Player", R.drawable.ic_apple_video),
        IconEntry("vlc", "VLC Media Player", R.drawable.ic_apple_vlc),
        IconEntry("audio", "Audio / Equalizer / DSP", R.drawable.ic_apple_audio),
        IconEntry("gallery", "Gallery / Photos", R.drawable.ic_apple_gallery),
        IconEntry("camera", "Camera / DVR / 360", R.drawable.ic_apple_camera),
        IconEntry("settings", "Car Settings", R.drawable.ic_apple_settings),
        IconEntry("files", "Files / File Manager", R.drawable.ic_apple_files),
        IconEntry("chrome", "Chrome", R.drawable.ic_apple_chrome),
        IconEntry("firefox", "Firefox", R.drawable.ic_apple_firefox),
        IconEntry("browser", "Browser (Safari)", R.drawable.ic_apple_safari),
        IconEntry("themestore", "Theme Store", R.drawable.ic_apple_themestore),
        IconEntry("cleaner", "System Cleaner / Clean", R.drawable.ic_apple_cleaner),
        IconEntry("clock", "Clock", R.drawable.ic_apple_clock),
        IconEntry("calendar", "Calendar", R.drawable.ic_apple_calendar),
        IconEntry("calculator", "Calculator", R.drawable.ic_apple_calculator),
        IconEntry("whatsapp", "WhatsApp", R.drawable.ic_apple_whatsapp),
        IconEntry("telegram", "Telegram", R.drawable.ic_apple_telegram),
        IconEntry("all_apps", "All Apps", R.drawable.ic_apple_all_apps)
    )

    private val ICON_MAP = ALL_ICONS.associateBy { it.id }

    private val wrappedCache = LruCache<String, Drawable>(120)

    fun getIconById(id: String): IconEntry? = ICON_MAP[id]

    /**
     * Resolves the custom squircle icon for a given package or component string.
     * 1. Slot icon override if passed.
     * 2. User manual app override (Prefs.customAppIcons).
     * 3. Automatic pattern-matching for common automotive and system apps.
     */
    fun getCustomIcon(context: Context, componentOrPkg: String, slotIconId: String = ""): Drawable? {
        // 1. Explicit slot icon override
        if (slotIconId.isNotEmpty()) {
            if (slotIconId == "default" || slotIconId == "none") return null
            val entry = ICON_MAP[slotIconId]
            if (entry != null) {
                return try { context.getDrawable(entry.drawableRes) } catch (t: Throwable) { null }
            }
        }

        val pkg = componentOrPkg.substringBefore('/')
        val lower = componentOrPkg.lowercase()

        // 2. User manual override per app
        val userOverrides = Prefs.customAppIcons(context)
        val overrideId = userOverrides[pkg] ?: userOverrides[componentOrPkg]
        if (overrideId != null) {
            if (overrideId == "default" || overrideId == "none") return null
            val entry = ICON_MAP[overrideId]
            if (entry != null) {
                return try { context.getDrawable(entry.drawableRes) } catch (t: Throwable) { null }
            }
        }

        // 3. Automatic matching for common head unit and system apps
        val matchedRes = when {
            // YouTube
            lower.contains("youtube") || lower.contains("newpipe") || lower.contains("smarttube") ->
                R.drawable.ic_apple_youtube

            // Netflix
            lower.contains("netflix") ->
                R.drawable.ic_apple_netflix

            // Spotify
            lower.contains("spotify") ->
                R.drawable.ic_apple_spotify

            // VLC Player
            lower.contains("vlc") ->
                R.drawable.ic_apple_vlc

            // Video Player
            lower.contains("video") || lower.contains("videoplayer") || lower.contains("mxplayer") ->
                R.drawable.ic_apple_video

            // Phone Mirroring (CarPlay / Android Auto / ZLink / AutoKit)
            lower.contains("androidauto") || lower.contains("zlink") || lower.contains("autokit") ->
                R.drawable.ic_apple_android_auto

            lower.contains("carplay") || lower.contains("carlink") || lower.contains("phoneconnect") ||
                    lower.contains("hicar") || lower.contains("easyconnect") ->
                R.drawable.ic_apple_carplay

            // Navigation / Maps (Google Maps, Waze, Apple Maps / generic)
            lower.contains("google.android.apps.maps") || lower.contains("google_maps") ->
                R.drawable.ic_apple_google_maps

            lower.contains("waze") ->
                R.drawable.ic_apple_waze

            lower.contains("map") || lower.contains("navi") || lower.contains("navigation") ||
                    lower.contains("amap") || lower.contains("autonavi") ||
                    lower.contains("sygic") || lower.contains("yandexnavi") ->
                R.drawable.ic_apple_maps

            // Radio
            lower.contains("radio") || lower.contains("fmam") || lower.contains("fmradio") || lower.contains("tuner") ->
                R.drawable.ic_apple_radio

            // Music
            lower.contains("music") || lower.contains("kuwo") || lower.contains("kugou") ||
                    lower.contains("qqmusic") || lower.contains("applemusic") ->
                R.drawable.ic_apple_music

            // AUX / AV-IN
            lower.contains("aux") || lower.contains("avin") ->
                R.drawable.ic_apple_aux

            // Audio / Equalizer / DSP / Sound settings
            lower.contains("equalizer") || lower.contains("eq") || lower.contains("dsp") ||
                    lower.contains("sound") || lower.contains("musicfx") || lower.contains("viper") ||
                    lower.contains("audiocontrol") ->
                R.drawable.ic_apple_audio

            // Gallery / Photos
            lower.contains("gallery") || lower.contains("photos") || lower.contains("photogallery") ||
                    lower.contains("quickpic") ->
                R.drawable.ic_apple_gallery

            // Camera / DVR / 360 view
            lower.contains("camera") || lower.contains("dvr") || lower.contains("dashcam") ||
                    lower.contains("360") || lower.contains("panoramic") || lower.contains("rearcam") ->
                R.drawable.ic_apple_camera

            // Phone / Dialer / Contacts
            lower.contains("dialer") || lower.contains("contacts") || lower.contains("phone") ->
                R.drawable.ic_apple_phone

            // Bluetooth
            lower.contains("bluetooth") || lower.contains("btmusic") || lower.contains("btsuite") ->
                R.drawable.ic_apple_bluetooth

            // Settings
            lower.contains("settings") || lower.contains("carsettings") || lower.contains("headunitsettings") ->
                R.drawable.ic_apple_settings

            // Files / File Manager (Stock Android DocumentsUI, Google Files, OEM File Managers)
            lower.contains("documentsui") || lower.contains("documents") ||
                    lower.contains("filemanager") || lower.contains("fileexplorer") ||
                    lower.contains("filebrowser") || lower.contains("files") ||
                    lower.contains("myfiles") || lower.contains("hidisk") ||
                    lower.contains("explorer") || lower.contains("totalcommander") ||
                    lower.contains("esfile") ->
                R.drawable.ic_apple_files

            // Web Browser (Chrome vs Firefox vs Safari)
            lower.contains("chrome") ->
                R.drawable.ic_apple_chrome

            lower.contains("firefox") || lower.contains("fennec") || lower.contains("org.mozilla") ->
                R.drawable.ic_apple_firefox

            lower.contains("browser") || lower.contains("opera") ||
                    lower.contains("safari") ->
                R.drawable.ic_apple_safari

            // Theme Store / Head unit Themes
            lower.contains("themestore") || lower.contains("theme") ->
                R.drawable.ic_apple_themestore

            // System Cleaner / Speed Boost / Head unit Cleaner (NX Sys Clean)
            lower.contains("nxsysclean") || lower.contains("sysclean") || lower.contains("cleaner") ||
                    lower.contains("clearcache") || lower.contains("clearram") || lower.contains("onekeyclean") ->
                R.drawable.ic_apple_cleaner

            // Clock / Alarm
            lower.contains("clock") || lower.contains("deskclock") || lower.contains("alarm") ->
                R.drawable.ic_apple_clock

            // Calendar
            lower.contains("calendar") ->
                R.drawable.ic_apple_calendar

            // Calculator
            lower.contains("calculator") || lower.contains("calc") ->
                R.drawable.ic_apple_calculator

            // WhatsApp
            lower.contains("whatsapp") ->
                R.drawable.ic_apple_whatsapp

            // Telegram
            lower.contains("telegram") ->
                R.drawable.ic_apple_telegram

            // Drawer
            componentOrPkg == Prefs.DRAWER ->
                R.drawable.ic_apple_all_apps

            else -> null
        }

        return if (matchedRes != null) {
            try { context.getDrawable(matchedRes) } catch (t: Throwable) { null }
        } else {
            null
        }
    }

    /**
     * Dynamically wraps an app's raw icon in an Apple dark charcoal (#1C1C1E) squircle plate
     * with a subtle hairline border, matching the Apple squircle aesthetic.
     */
    fun wrapInSquirclePlate(context: Context, key: String, rawIcon: Drawable): Drawable {
        wrappedCache.get(key)?.let { return it }

        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Apple squircle path
        val path = Path()
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val radius = size * 0.225f
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.clipPath(path)

        // 1. Apple dark charcoal plate
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1C1C1E.toInt()
        }
        canvas.drawRect(rect, bgPaint)

        // 2. Draw raw icon centered at ~62% of tile size
        val iconSize = (size * 0.62f).toInt()
        val pad = (size - iconSize) / 2
        rawIcon.setBounds(pad, pad, pad + iconSize, pad + iconSize)
        rawIcon.draw(canvas)

        // 3. Subtle hairline stroke
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0x1AFFFFFF
        }
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        val result = BitmapDrawable(context.resources, bitmap)
        wrappedCache.put(key, result)
        return result
    }

    /**
     * Resolves the icon for display across all launcher components (Dock, Drawer, Picker, Setup):
     * 1. Returns custom Apple squircle icon if matched or configured.
     * 2. Otherwise, automatically wraps the app's raw icon in an Apple squircle background plate.
     */
    fun resolveIcon(
        context: Context,
        componentOrPkg: String,
        slotIconId: String = "",
        rawIcon: Drawable? = null
    ): Drawable {
        val custom = getCustomIcon(context, componentOrPkg, slotIconId)
        if (custom != null) return custom

        val actualRaw = rawIcon ?: try {
            val pkg = componentOrPkg.substringBefore('/')
            if (componentOrPkg.contains('/')) {
                val cn = ComponentName.unflattenFromString(componentOrPkg)
                if (cn != null) context.packageManager.getActivityIcon(cn)
                else context.packageManager.getApplicationIcon(pkg)
            } else {
                context.packageManager.getApplicationIcon(pkg)
            }
        } catch (t: Throwable) {
            null
        }

        if (actualRaw != null) {
            return wrapInSquirclePlate(context, componentOrPkg, actualRaw)
        }

        val fallback = getIconById("all_apps")
        return if (fallback != null) context.getDrawable(fallback.drawableRes)!!
        else BitmapDrawable(context.resources, Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888))
    }
}
