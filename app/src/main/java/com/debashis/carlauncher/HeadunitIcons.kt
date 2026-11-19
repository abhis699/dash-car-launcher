package com.debashis.carlauncher

import android.content.Context
import android.graphics.drawable.Drawable

/**
 * Built-in custom Apple-style squircle icons tailored to car dashboards.
 *
 * Full-bleed squircle icons matching CarPlay and modern iOS aesthetics:
 * Maps, Google Maps, YouTube, Netflix, Music, Spotify, Radio, AUX, Phone,
 * CarPlay/Android Auto, Video, Settings, Safari, Chrome, and All Apps.
 */
object HeadunitIcons {

    data class IconEntry(val id: String, val label: String, val drawableRes: Int)

    val ALL_ICONS = listOf(
        IconEntry("maps", "Navigation (Apple Maps)", R.drawable.ic_apple_maps),
        IconEntry("google_maps", "Google Maps", R.drawable.ic_apple_google_maps),
        IconEntry("youtube", "YouTube", R.drawable.ic_apple_youtube),
        IconEntry("netflix", "Netflix", R.drawable.ic_apple_netflix),
        IconEntry("music", "Music (Apple Music)", R.drawable.ic_apple_music),
        IconEntry("spotify", "Spotify", R.drawable.ic_apple_spotify),
        IconEntry("radio", "FM / AM Radio", R.drawable.ic_apple_radio),
        IconEntry("aux", "AUX / AV-IN", R.drawable.ic_apple_aux),
        IconEntry("phone", "Phone / Bluetooth", R.drawable.ic_apple_phone),
        IconEntry("mirroring", "CarPlay / Android Auto", R.drawable.ic_apple_carplay),
        IconEntry("video", "Video Player", R.drawable.ic_apple_video),
        IconEntry("settings", "Car Settings", R.drawable.ic_apple_settings),
        IconEntry("browser", "Browser (Safari)", R.drawable.ic_apple_safari),
        IconEntry("chrome", "Chrome", R.drawable.ic_apple_chrome),
        IconEntry("all_apps", "All Apps", R.drawable.ic_apple_all_apps)
    )

    private val ICON_MAP = ALL_ICONS.associateBy { it.id }

    fun getIconById(id: String): IconEntry? = ICON_MAP[id]

    /**
     * Resolves the custom squircle icon for a given package or component string.
     * 1. Slot icon override if passed.
     * 2. User manual app override (Prefs.customAppIcons).
     * 3. Automatic pattern-matching for common automotive apps.
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

        // 3. Automatic matching for common head unit apps
        val matchedRes = when {
            // YouTube (check before video)
            lower.contains("youtube") || lower.contains("newpipe") || lower.contains("smarttube") ->
                R.drawable.ic_apple_youtube

            // Netflix
            lower.contains("netflix") ->
                R.drawable.ic_apple_netflix

            // Phone Mirroring (CarPlay / Android Auto / ZLink / AutoKit)
            lower.contains("carplay") || lower.contains("androidauto") || lower.contains("carlink") ||
                lower.contains("zlink") || lower.contains("autokit") || lower.contains("phoneconnect") ||
                lower.contains("hicar") || lower.contains("easyconnect") ->
                R.drawable.ic_apple_carplay

            // Navigation / Maps (Google Maps vs generic/Apple)
            lower.contains("google.android.apps.maps") || lower.contains("google_maps") ->
                R.drawable.ic_apple_google_maps

            lower.contains("map") || lower.contains("navi") || lower.contains("navigation") ||
                lower.contains("waze") || lower.contains("amap") || lower.contains("autonavi") ||
                lower.contains("sygic") || lower.contains("yandexnavi") ->
                R.drawable.ic_apple_maps

            // Radio
            lower.contains("radio") || lower.contains("fmam") || lower.contains("fmradio") ->
                R.drawable.ic_apple_radio

            // Music / Spotify
            lower.contains("spotify") ->
                R.drawable.ic_apple_spotify

            lower.contains("music") || lower.contains("kuwo") ->
                R.drawable.ic_apple_music

            // AUX / AV-IN
            lower.contains("aux") || lower.contains("avin") ->
                R.drawable.ic_apple_aux

            // Video Player
            lower.contains("video") || lower.contains("gallery3d") || lower.contains("vlc") ||
                lower.contains("mxplayer") || lower.contains("videoplayer") ->
                R.drawable.ic_apple_video

            // Phone / Dialer / Bluetooth
            lower.contains("dialer") || lower.contains("contacts") || lower.contains("phone") ||
                lower.contains("bluetooth") || lower.contains("btmusic") ->
                R.drawable.ic_apple_phone

            // Settings
            lower.contains("settings") ->
                R.drawable.ic_apple_settings

            // Web Browser (Chrome vs Safari)
            lower.contains("chrome") ->
                R.drawable.ic_apple_chrome

            lower.contains("browser") || lower.contains("firefox") || lower.contains("opera") ||
                lower.contains("safari") ->
                R.drawable.ic_apple_safari

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
}
