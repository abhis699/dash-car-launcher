package com.debashis.carlauncher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Detects which firmware family a head unit is running, so the dock can be pre-filled with
 * something that actually resolves instead of eight dimmed tiles pointing at imotor packages.
 *
 * Supports major Chinese & OEM Android head unit architectures:
 * - MediaTek K80 BSP (alps k80_bsp / full_k80_bsp / MT6580)
 * - Topway TS10 / TS18 / TS7 (Spreadtrum / UniSoC UIS7862)
 * - FYT / UIS7862 / Teyes (CC2 / CC3 / Spro / Joying / Mekede)
 * - Allwinner T3 / T507 / T8
 * - Rockchip PX5 / PX6 / RK3399 / HCT
 * - MTK AC8227L / AC8257 (AutoChips)
 * - imotor (RK3326)
 */
object VendorProfiles {

    enum class Role { PHONE_MIRRORING, NAVIGATION, MUSIC, RADIO, PHONE, AUX, VIDEO, SETTINGS, BLUETOOTH }

    /**
     * One firmware family. [candidates] is checked in order per role, first match wins,
     * because a family sometimes ships more than one plausible component for the same job
     * (imotor's phoneconnect package alone exposes four launcher activities).
     */
    data class Profile(val family: String, val candidates: Map<Role, List<String>>)

    data class Result(
        val family: String,
        val filled: Int,
        val attempted: Int,
        val resolved: Map<Role, String>
    )

    // ---------------------------------------------------------------- profiles

    /**
     * MediaTek K80 BSP (alps k80_bsp / full_k80_bsp / MT6580):
     * Found on budget Android car head units and low-cost automotive tablets.
     * Uses Mediatek FM radio, stock/MTK music, MTK camera/AUX, and ZLink/SpeedPlay mirroring.
     */
    private val K80_BSP = Profile(
        family = "MediaTek K80 (alps)",
        candidates = mapOf(
            Role.PHONE_MIRRORING to listOf(
                "com.zjinnova.zlink",
                "com.carlink.carplay",
                "com.hct.carplay",
                "com.suding.speedplay",
                "com.papago.zlink"
            ),
            Role.NAVIGATION to listOf(
                "com.google.android.apps.maps",
                "com.autonavi.amapauto",
                "com.waze",
                "com.baidu.BaiduMap"
            ),
            Role.MUSIC to listOf(
                "com.android.music",
                "com.mediatek.music",
                "cn.kuwo.player",
                "com.spotify.music"
            ),
            Role.RADIO to listOf(
                "com.android.fmradio",
                "com.mediatek.fmradio"
            ),
            Role.PHONE to listOf(
                "com.android.dialer",
                "com.android.contacts",
                "com.mediatek.autodialer"
            ),
            Role.AUX to listOf(
                "com.android.aux",
                "com.mediatek.camera",
                "com.mediatek.aux"
            ),
            Role.VIDEO to listOf(
                "com.google.android.youtube",
                "app.morphe.android.youtube",
                "com.android.gallery3d",
                "com.mediatek.videoplayer"
            ),
            Role.SETTINGS to listOf(
                "com.android.settings",
                "com.mediatek.engineermode"
            ),
            Role.BLUETOOTH to listOf(
                "com.android.bluetooth",
                "com.mediatek.bluetooth"
            )
        )
    )

    /**
     * Topway TS10 / TS18 / TS7:
     * Extremely common modern Chinese head unit platform (Spreadtrum/UniSoC UIS7862/TS10).
     */
    private val TOPWAY = Profile(
        family = "Topway (TS10/TS18)",
        candidates = mapOf(
            Role.PHONE_MIRRORING to listOf(
                "com.ts.main/com.ts.main.carplay.CarPlayActivity",
                "com.zjinnova.zlink",
                "com.carlink.carplay",
                "com.eny.carplay",
                "com.ts.carplay"
            ),
            Role.NAVIGATION to listOf(
                "com.google.android.apps.maps",
                "com.autonavi.amapauto",
                "com.waze"
            ),
            Role.MUSIC to listOf(
                "com.ts.music",
                "com.android.music",
                "cn.kuwo.player",
                "com.spotify.music"
            ),
            Role.RADIO to listOf(
                "com.ts.radio",
                "com.android.fmradio"
            ),
            Role.PHONE to listOf(
                "com.ts.bluetooth/com.ts.bt.BtPhoneActivity",
                "com.ts.bluetooth",
                "com.android.contacts",
                "com.android.dialer"
            ),
            Role.AUX to listOf(
                "com.ts.aux",
                "com.ts.avin"
            ),
            Role.VIDEO to listOf(
                "com.ts.video",
                "com.google.android.youtube",
                "com.android.gallery3d"
            ),
            Role.SETTINGS to listOf(
                "com.ts.carsettings",
                "com.ts.main",
                "com.android.settings"
            ),
            Role.BLUETOOTH to listOf(
                "com.ts.bluetooth",
                "com.android.bluetooth"
            )
        )
    )

    /**
     * FYT / UIS7862 / Teyes (CC2/CC3/Spro):
     * The SYU ecosystem used by Joying, Mekede, Navifly, and Teyes.
     */
    private val FYT = Profile(
        family = "FYT / Teyes",
        candidates = mapOf(
            Role.PHONE_MIRRORING to listOf(
                "com.syu.carlink",
                "com.zjinnova.zlink",
                "com.mixtorrent.carplayer",
                "com.hct.carplay.cp"
            ),
            Role.NAVIGATION to listOf(
                "com.google.android.apps.maps",
                "com.waze",
                "com.autonavi.amapauto",
                "ru.yandex.yandexnavi"
            ),
            Role.MUSIC to listOf(
                "com.syu.music",
                "com.android.music",
                "cn.kuwo.player",
                "com.spotify.music"
            ),
            Role.RADIO to listOf(
                "com.syu.radio",
                "com.android.fmradio"
            ),
            Role.PHONE to listOf(
                "com.syu.bt/com.syu.bt.ActBt",
                "com.syu.bt",
                "com.android.contacts",
                "com.android.dialer"
            ),
            Role.AUX to listOf(
                "com.syu.av",
                "com.syu.aux",
                "com.android.aux"
            ),
            Role.VIDEO to listOf(
                "com.syu.video",
                "com.google.android.youtube",
                "com.android.gallery3d"
            ),
            Role.SETTINGS to listOf(
                "com.syu.canbus",
                "com.syu.settings",
                "com.android.settings"
            ),
            Role.BLUETOOTH to listOf(
                "com.syu.bt",
                "com.android.bluetooth"
            )
        )
    )

    /**
     * Allwinner T3 / T507 / T8:
     * Popular low-to-mid range Android head units.
     */
    private val ALLWINNER = Profile(
        family = "Allwinner",
        candidates = mapOf(
            Role.PHONE_MIRRORING to listOf(
                "com.zjinnova.zlink",
                "com.allwinner.carplay",
                "com.carlink.carplay"
            ),
            Role.NAVIGATION to listOf(
                "com.google.android.apps.maps",
                "com.autonavi.amapauto",
                "com.waze"
            ),
            Role.MUSIC to listOf(
                "com.aw.music",
                "com.allwinner.music",
                "com.android.music"
            ),
            Role.RADIO to listOf(
                "com.aw.radio",
                "com.allwinner.radio",
                "com.android.fmradio"
            ),
            Role.PHONE to listOf(
                "com.aw.bluetooth",
                "com.android.dialer",
                "com.android.contacts"
            ),
            Role.AUX to listOf(
                "com.aw.aux",
                "com.allwinner.aux"
            ),
            Role.VIDEO to listOf(
                "com.aw.video",
                "com.allwinner.video",
                "com.google.android.youtube"
            ),
            Role.SETTINGS to listOf(
                "com.aw.settings",
                "com.android.settings"
            ),
            Role.BLUETOOTH to listOf(
                "com.aw.bluetooth",
                "com.android.bluetooth"
            )
        )
    )

    /**
     * Rockchip PX5 / PX6 / RK3399 / HCT:
     * MTCB / MTCD / MTCE / HCT head units (Dasaita, Eonon, Xtrons).
     */
    private val ROCKCHIP = Profile(
        family = "Rockchip (PX5/PX6/HCT)",
        candidates = mapOf(
            Role.PHONE_MIRRORING to listOf(
                "com.zjinnova.zlink",
                "com.hct.carplay",
                "com.microntek.carplay"
            ),
            Role.NAVIGATION to listOf(
                "com.google.android.apps.maps",
                "com.autonavi.amapauto",
                "com.waze"
            ),
            Role.MUSIC to listOf(
                "com.hct.music",
                "com.microntek.music",
                "com.android.music"
            ),
            Role.RADIO to listOf(
                "com.hct.radio",
                "com.microntek.radio",
                "com.android.fmradio"
            ),
            Role.PHONE to listOf(
                "com.hct.bluetooth",
                "com.microntek.bluetooth",
                "com.android.dialer",
                "com.android.contacts"
            ),
            Role.AUX to listOf(
                "com.hct.aux",
                "com.microntek.aux"
            ),
            Role.VIDEO to listOf(
                "com.hct.video",
                "com.microntek.video",
                "com.google.android.youtube"
            ),
            Role.SETTINGS to listOf(
                "com.hct.settings",
                "com.android.settings"
            ),
            Role.BLUETOOTH to listOf(
                "com.hct.bluetooth",
                "com.microntek.bluetooth",
                "com.android.bluetooth"
            )
        )
    )

    /**
     * MTK AC8227L / AC8257:
     * AutoChips / MediaTek platforms widely found in entry-level 7-inch to 10-inch car radios.
     */
    private val MTK_AC8227L = Profile(
        family = "MTK / AutoChips",
        candidates = mapOf(
            Role.PHONE_MIRRORING to listOf(
                "com.zjinnova.zlink",
                "com.autochips.carplay",
                "com.mtcmobi.hicar",
                "com.mtk.carplay"
            ),
            Role.NAVIGATION to listOf(
                "com.google.android.apps.maps",
                "com.waze",
                "com.autonavi.amapauto"
            ),
            Role.MUSIC to listOf(
                "com.autochips.music",
                "com.android.music"
            ),
            Role.RADIO to listOf(
                "com.autochips.radio",
                "com.android.fmradio"
            ),
            Role.PHONE to listOf(
                "com.autochips.bluetooth",
                "com.android.contacts",
                "com.android.dialer"
            ),
            Role.AUX to listOf(
                "com.autochips.aux",
                "com.mtk.aux"
            ),
            Role.VIDEO to listOf(
                "com.autochips.video",
                "com.google.android.youtube",
                "com.android.gallery3d"
            ),
            Role.SETTINGS to listOf(
                "com.autochips.settings",
                "com.android.settings"
            ),
            Role.BLUETOOTH to listOf(
                "com.autochips.bluetooth",
                "com.android.bluetooth"
            )
        )
    )

    /**
     * imotor: VERIFIED on a real RK3326 unit (see README, "Seven bugs"). Component-level
     * targeting is required for phoneconnect because the package default resolves to the
     * wrong activity and the generic Android robot icon.
     */
    private val IMOTOR = Profile(
        family = "imotor",
        candidates = mapOf(
            Role.PHONE_MIRRORING to listOf(
                "com.imotor.phoneconnect/com.imotor.phoneconnect.Carplay",
                "com.imotor.phoneconnect/com.imotor.phoneconnect.AndroidAuto"
            ),
            Role.NAVIGATION to listOf(
                "com.google.android.apps.maps",
                "com.autonavi.amapauto",
                "com.waze",
                "com.here.app.maps"
            ),
            Role.MUSIC to listOf("com.imotor.music", "com.spotify.music"),
            Role.RADIO to listOf("com.imotor.fmam"),
            Role.PHONE to listOf("com.imotor.contacts/com.imotor.contacts.ui.MainActivity"),
            Role.AUX to listOf("com.imotor.aux", "com.imotor.avin"),
            Role.VIDEO to listOf("com.google.android.youtube", "com.imotor.video", "org.videolan.vlc"),
            Role.SETTINGS to listOf("com.android.settings"),
            Role.BLUETOOTH to listOf("com.imotor.btmusic")
        )
    )

    private val NAMED_PROFILES = listOf(K80_BSP, TOPWAY, FYT, ALLWINNER, ROCKCHIP, MTK_AC8227L, IMOTOR)

    /**
     * Package-name substrings for the generic fallback pass. Deliberately loose: this is
     * "does the package name suggest this role", not a lookup table, so it is expected to
     * both miss real matches and occasionally grab the wrong app. It only runs after every
     * named profile has been tried and only fills roles still empty, so it can only add
     * something rather than override a real match.
     */
    private val GENERIC_HINTS: Map<Role, List<String>> = mapOf(
        Role.PHONE_MIRRORING to listOf("carplay", "androidauto", "carlink", "hicar", "airplay", "zlink", "autokit", "speedplay"),
        Role.NAVIGATION to listOf("maps", "map", "navi", "navigation", "waze", "amap", "autonavi", "sygic", "yandexnavi"),
        Role.MUSIC to listOf("music", "player", "mediaplayer", "spotify", "kuwo", "kugou"),
        Role.RADIO to listOf("radio", "fmam", "fmradio", "tuner"),
        Role.PHONE to listOf("contacts", "dialer", "phone", "btphone"),
        Role.AUX to listOf("aux", "avin", "av"),
        Role.VIDEO to listOf("youtube", "video", "gallery", "filemanager", "vlc"),
        Role.SETTINGS to listOf("carsettings", "canbus", "settings", "engineermode"),
        Role.BLUETOOTH to listOf("btmusic", "bluetooth", "btsuite")
    )

    /**
     * Checks if the device's system build properties identify it as an alps / k80 MediaTek board.
     */
    fun isK80Hardware(): Boolean {
        val board = Build.BOARD.orEmpty().lowercase()
        val product = Build.PRODUCT.orEmpty().lowercase()
        val hardware = Build.HARDWARE.orEmpty().lowercase()
        val device = Build.DEVICE.orEmpty().lowercase()
        val model = Build.MODEL.orEmpty().lowercase()
        return board.contains("k80") || product.contains("k80") ||
                hardware.contains("k80") || device.contains("k80") ||
                board.contains("alps") || product.contains("alps") ||
                hardware.contains("mt6580") || model.contains("k80")
    }

    // ------------------------------------------------------------------ detect

    /**
     * Never throws. Every PackageManager call is wrapped so a single odd component on an
     * unfamiliar unit cannot take down a screen that exists specifically to help unfamiliar
     * units.
     */
    fun detect(pm: PackageManager): Result {
        var best: Result? = null

        val profilesToTry = if (isK80Hardware()) {
            listOf(K80_BSP) + NAMED_PROFILES.filter { it != K80_BSP }
        } else {
            NAMED_PROFILES
        }

        for (profile in profilesToTry) {
            val resolved = HashMap<Role, String>()
            for ((role, candidates) in profile.candidates) {
                val match = candidates.firstOrNull { isUsable(pm, it) }
                if (match != null) resolved[role] = match
            }
            val attempted = profile.candidates.size
            if (best == null || resolved.size > best.filled) {
                best = Result(profile.family, resolved.size, attempted, resolved)
            }
        }

        // A named profile that matched nothing is worse than useless: it labels the unit
        // with a family name that is wrong. Fall through to the generic pass instead.
        val current = best
        if (current == null || current.filled == 0) {
            return genericFallback(pm)
        }

        // Even a partial named match is topped up by the generic pass for whatever roles
        // it left empty, so e.g. a real imotor unit still gets Video filled if that role
        // was not in the profile's candidate list.
        val topUp = genericFallback(pm, alreadyResolved = current.resolved)
        return Result(current.family, topUp.resolved.size, GENERIC_HINTS.size, topUp.resolved)
    }

    private fun genericFallback(
        pm: PackageManager,
        alreadyResolved: Map<Role, String> = emptyMap()
    ): Result {
        val resolved = HashMap<Role, String>(alreadyResolved)
        val launchable = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
        } catch (e: Exception) {
            emptyList()
        }

        for ((role, hints) in GENERIC_HINTS) {
            if (resolved.containsKey(role)) continue
            for (info in launchable) {
                val pkg = try {
                    info.activityInfo.packageName
                } catch (e: Exception) {
                    continue
                }
                if (hints.any { pkg.contains(it, ignoreCase = true) }) {
                    resolved[role] = pkg
                    break
                }
            }
        }
        return Result("Generic Android", resolved.size, GENERIC_HINTS.size, resolved)
    }

    /** A candidate counts only if it is installed AND launchable, never just "present". */
    private fun isUsable(pm: PackageManager, component: String): Boolean {
        return try {
            if (component.contains('/')) {
                val pkg = component.substringBefore('/')
                val cls = component.substringAfter('/')
                val info = pm.getActivityInfo(ComponentName(pkg, cls), 0)
                info.enabled
            } else {
                pm.getLaunchIntentForPackage(component) != null
            }
        } catch (e: Throwable) {
            false
        }
    }

    // -------------------------------------------------------------- dock slots

    /**
     * Fixed slot order chosen to match what a driver reaches for most: mirroring and music up
     * front, drawer always last. Roles with no match leave the slot blank rather than guessing,
     * since a blank tile is honest and a wrong one is a tap that does nothing useful while
     * driving.
     */
    val SLOT_ORDER = listOf(
        Role.PHONE_MIRRORING, Role.NAVIGATION, Role.MUSIC, Role.RADIO,
        Role.PHONE, Role.VIDEO, Role.SETTINGS
    )

    fun toDockComponents(resolved: Map<Role, String>): List<String> {
        val out = ArrayList<String>(Prefs.SLOTS)
        for (i in 0 until Prefs.SLOTS - 1) {
            val role = SLOT_ORDER.getOrNull(i)
            out.add(if (role != null) resolved[role].orEmpty() else "")
        }
        out.add(Prefs.DRAWER)
        return out
    }

    /** Always returns exactly [Prefs.SLOTS] entries, with the drawer marker in the last one. */
    fun toDockComponents(result: Result): List<String> = toDockComponents(result.resolved)
}
