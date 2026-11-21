package com.debashis.carlauncher

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Universal media playback capture engine for Android 8.1+ (API 27+).
 *
 * Integrates:
 * 1. MediaSessionManager (Spotify, YouTube, Apple Music, VLC, podcasts, etc.)
 * 2. NotificationListenerService (captures MediaSession tokens and media notifications from browsers/players)
 * 3. Headunit & standard Android broadcasts (AVRCP, Bluetooth A2DP, FYT/Topway/stock Radio & Music)
 * 4. AudioManager AudioPlaybackCallback & universal hardware media key dispatch
 * 5. Async URI artwork decoding with memory caching
 */
object MediaManager {

    data class MediaState(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val art: Bitmap? = null,
        val isPlaying: Boolean = false,
        val canSkip: Boolean = true,
        val packageName: String? = null,
        val hasActiveSession: Boolean = false
    )

    interface Listener {
        fun onMediaChanged(state: MediaState)
    }

    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()

    private val listeners = Collections.synchronizedList(mutableListOf<Listener>())

    private var sessionManager: MediaSessionManager? = null
    private var currentController: MediaController? = null
    private var activeControllers: List<MediaController> = emptyList()

    private val artCache = Collections.synchronizedMap(object : LinkedHashMap<String, Bitmap>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            return size > 25
        }
    })

    private var currentArtUri: String? = null
    private var currentLoadedArt: Bitmap? = null

    // Fallback broadcast state
    private var broadcastTitle: String? = null
    private var broadcastArtist: String? = null
    private var broadcastAlbum: String? = null
    private var broadcastPlaying: Boolean = false
    private var broadcastPackage: String? = null
    private var lastBroadcastTime: Long = 0

    // Fallback notification state
    private class NotificationMedia(
        val packageName: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val art: Bitmap?,
        val isPlaying: Boolean,
        val playPauseIntent: PendingIntent?,
        val nextIntent: PendingIntent?,
        val prevIntent: PendingIntent?
    )
    private var notificationMedia: NotificationMedia? = null

    private var notificationListener: NotificationListenerService? = null
    private var isAudioActive: Boolean = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        activeControllers = list ?: emptyList()
        reevaluateController()
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            extractArtFromMetadata(metadata)
            notifyListeners()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            notifyListeners()
        }

        override fun onSessionDestroyed() {
            reevaluateController()
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            intent ?: return
            val action = intent.action ?: return

            if (action.contains("PLAYING_STATE_CHANGED")) {
                val state = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1)
                if (state == 10) { // 10 = BluetoothA2dp.STATE_PLAYING
                    broadcastPlaying = true
                } else if (state == 11 || state == 0) { // 11 = STATE_NOT_PLAYING, 0 = STATE_DISCONNECTED
                    broadcastPlaying = false
                }
            }

            val track = intent.getStringExtra("track")
                ?: intent.getStringExtra("title")
                ?: intent.getStringExtra("song")
                ?: intent.getStringExtra("music_name")
            val artist = intent.getStringExtra("artist")
                ?: intent.getStringExtra("singer")
                ?: intent.getStringExtra("singer_name")
            val album = intent.getStringExtra("album")

            val playing = when {
                intent.hasExtra("playing") -> {
                    try { intent.getBooleanExtra("playing", false) } catch (e: Throwable) { intent.getIntExtra("playing", 0) == 1 }
                }
                intent.hasExtra("playstate") -> intent.getIntExtra("playstate", 0) == 1
                intent.hasExtra("playStatus") -> intent.getIntExtra("playStatus", 0) == 1
                else -> broadcastPlaying
            }

            if (!track.isNullOrBlank() || !artist.isNullOrBlank()) {
                broadcastTitle = usable(track)
                broadcastArtist = usable(artist)
                broadcastAlbum = usable(album)
                broadcastPlaying = playing
                broadcastPackage = intent.`package`
                lastBroadcastTime = System.currentTimeMillis()
                notifyListeners()
            }
        }
    }

    private var broadcastRegistered = false
    private var audioCallbackRegistered = false

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext

        registerBroadcastReceiver()
        registerAudioPlaybackCallback()
        setupSessionManager()
    }

    fun hasNotificationAccess(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(context.packageName)
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        listener.onMediaChanged(getCurrentState())
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun onNotificationListenerConnected(service: NotificationListenerService) {
        notificationListener = service
        setupSessionManager()
        queryActiveNotifications(service)
    }

    fun onNotificationListenerDisconnected() {
        notificationListener = null
    }

    fun onNotificationPosted(service: NotificationListenerService, sbn: StatusBarNotification) {
        val extras = sbn.notification.extras ?: return

        // 1. Check for MediaSession token in notification extras
        val token = extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        if (token != null) {
            try {
                val controller = MediaController(service, token)
                val exists = activeControllers.any { it.sessionToken == token }
                if (!exists) {
                    activeControllers = activeControllers + controller
                    reevaluateController()
                }
            } catch (t: Throwable) {}
        }

        // 2. Check for media notifications (browsers, video players, radio apps)
        inspectMediaNotification(sbn)
    }

    fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (notificationMedia?.packageName == sbn.packageName) {
            notificationMedia = null
            notifyListeners()
        }
    }

    private fun inspectMediaNotification(sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        val title = usable(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        val text = usable(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        val subText = usable(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())

        val actions = notif.actions
        var playPauseIntent: PendingIntent? = null
        var nextIntent: PendingIntent? = null
        var prevIntent: PendingIntent? = null
        var isPlaying = false

        if (actions != null) {
            for (action in actions) {
                val actionTitle = action.title?.toString()?.lowercase() ?: ""
                if (actionTitle.contains("pause") || actionTitle.contains("stop")) {
                    playPauseIntent = action.actionIntent
                    isPlaying = true
                } else if (actionTitle.contains("play") || actionTitle.contains("resume")) {
                    if (playPauseIntent == null) playPauseIntent = action.actionIntent
                } else if (actionTitle.contains("next") || actionTitle.contains("skip")) {
                    nextIntent = action.actionIntent
                } else if (actionTitle.contains("prev")) {
                    prevIntent = action.actionIntent
                }
            }
        }

        val isMediaStyle = notif.category == Notification.CATEGORY_TRANSPORT ||
                actions?.any {
                    val t = it.title?.toString()?.lowercase() ?: ""
                    t.contains("play") || t.contains("pause") || t.contains("skip") || t.contains("next")
                } == true

        if (isMediaStyle && (title != null || text != null)) {
            val artBitmap = (extras.getParcelable(Notification.EXTRA_LARGE_ICON) as? Bitmap)
                ?: extractIconBitmap(sbn.notification.getLargeIcon())

            notificationMedia = NotificationMedia(
                packageName = sbn.packageName,
                title = title,
                artist = text,
                album = subText,
                art = artBitmap,
                isPlaying = isPlaying,
                playPauseIntent = playPauseIntent,
                nextIntent = nextIntent,
                prevIntent = prevIntent
            )
            notifyListeners()
        }
    }

    private fun extractIconBitmap(icon: Icon?): Bitmap? {
        if (icon == null) return null
        val ctx = appContext ?: return null
        return try {
            val drawable = icon.loadDrawable(ctx)
            if (drawable is BitmapDrawable) drawable.bitmap else null
        } catch (t: Throwable) {
            null
        }
    }

    private fun queryActiveNotifications(service: NotificationListenerService) {
        try {
            val active = service.activeNotifications ?: return
            for (sbn in active) {
                onNotificationPosted(service, sbn)
            }
        } catch (t: Throwable) {}
    }

    private fun setupSessionManager() {
        val ctx = appContext ?: return
        if (!hasNotificationAccess(ctx)) return

        if (sessionManager == null) {
            sessionManager = try {
                ctx.getSystemService(MediaSessionManager::class.java)
            } catch (t: Throwable) {
                null
            }
        }

        val component = ComponentName(ctx, DashNotificationListener::class.java)
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, component)
            activeControllers = sessionManager?.getActiveSessions(component) ?: emptyList()
            reevaluateController()
        } catch (t: Throwable) {}
    }

    private fun registerBroadcastReceiver() {
        val ctx = appContext ?: return
        if (broadcastRegistered) return

        val filter = IntentFilter().apply {
            addAction("com.android.music.metachanged")
            addAction("com.android.music.playstatechanged")
            addAction("com.android.music.playbackcomplete")
            addAction("com.htc.music.metachanged")
            addAction("fm.last.android.metachanged")
            addAction("com.sec.android.app.music.metachanged")
            addAction("com.nullsoft.winamp.metachanged")
            addAction("com.amazon.mp3.metachanged")
            addAction("com.miui.player.metachanged")
            addAction("com.real.IMP.metachanged")
            addAction("com.sonyericsson.music.metachanged")
            addAction("com.rdio.android.metachanged")
            addAction("com.samsung.sec.android.MusicPlayer.metachanged")
            addAction("com.andrew.apollo.metachanged")
            addAction("com.spotify.music.playbackstatechanged")
            addAction("com.spotify.music.metadatachanged")
            addAction("android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED")
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("com.fyt.system.bt")
            addAction("com.syu.bt.by")
            addAction("com.syu.music")
            addAction("com.syu.radio")
            addAction("com.microntek.music")
            addAction("com.microntek.radio")
            addAction("com.tw.radio")
        }

        try {
            ctx.registerReceiver(broadcastReceiver, filter)
            broadcastRegistered = true
        } catch (t: Throwable) {}
    }

    private fun registerAudioPlaybackCallback() {
        val ctx = appContext ?: return
        if (audioCallbackRegistered) return

        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                am.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                        val active = configs.any {
                            val u = it.audioAttributes.usage
                            u == AudioAttributes.USAGE_MEDIA ||
                                    u == AudioAttributes.USAGE_GAME ||
                                    u == AudioAttributes.USAGE_UNKNOWN
                        }
                        isAudioActive = active
                        notifyListeners()
                    }
                }, mainHandler)
                audioCallbackRegistered = true
            } catch (t: Throwable) {}
        }
    }

    private fun scoreController(c: MediaController): Int {
        val state = c.playbackState?.state ?: PlaybackState.STATE_NONE
        val isPlaying = state == PlaybackState.STATE_PLAYING ||
                state == PlaybackState.STATE_BUFFERING ||
                state == PlaybackState.STATE_FAST_FORWARDING ||
                state == PlaybackState.STATE_REWINDING
        val meta = c.metadata
        val hasTitle = !meta?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() ||
                !meta?.description?.title.isNullOrBlank()
        val hasArt = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null ||
                meta?.getBitmap(MediaMetadata.METADATA_KEY_ART) != null ||
                !meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).isNullOrBlank() ||
                !meta?.description?.iconUri?.toString().isNullOrBlank()

        return when {
            isPlaying && hasTitle -> 100
            isPlaying -> 85
            state == PlaybackState.STATE_PAUSED && hasTitle && hasArt -> 65
            state == PlaybackState.STATE_PAUSED && hasTitle -> 55
            hasTitle && hasArt -> 45
            hasTitle -> 35
            state == PlaybackState.STATE_PAUSED -> 20
            else -> 0
        }
    }

    private fun reevaluateController() {
        val list = activeControllers
        val best = list.maxByOrNull { scoreController(it) }

        if (best != currentController) {
            currentController?.unregisterCallback(controllerCallback)
            currentController = best
            currentController?.registerCallback(controllerCallback)
            extractArtFromMetadata(best?.metadata)
        } else if (best != null && currentLoadedArt == null) {
            extractArtFromMetadata(best.metadata)
        }

        notifyListeners()
    }

    private fun extractArtFromMetadata(metadata: MediaMetadata?) {
        if (metadata == null) {
            currentLoadedArt = null
            currentArtUri = null
            return
        }

        val directBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: metadata.description?.iconBitmap

        if (directBitmap != null) {
            currentLoadedArt = directBitmap
            currentArtUri = null
            return
        }

        val uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: metadata.description?.iconUri?.toString()

        if (uriStr.isNullOrBlank()) {
            currentLoadedArt = null
            currentArtUri = null
            return
        }

        if (uriStr == currentArtUri && currentLoadedArt != null) return

        currentArtUri = uriStr
        artCache[uriStr]?.let {
            currentLoadedArt = it
            return
        }

        val ctx = appContext ?: return
        bgExecutor.execute {
            val bmp = loadBitmap(ctx, uriStr)
            if (bmp != null) {
                artCache[uriStr] = bmp
                mainHandler.post {
                    if (currentArtUri == uriStr) {
                        currentLoadedArt = bmp
                        notifyListeners()
                    }
                }
            }
        }
    }

    private fun loadBitmap(context: Context, uriStr: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriStr)
            val stream: InputStream? = if (uri.scheme == "http" || uri.scheme == "https") {
                val conn = URL(uriStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.doInput = true
                conn.connect()
                conn.inputStream
            } else {
                context.contentResolver.openInputStream(uri)
            }
            stream?.use {
                val bytes = it.readBytes()
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                var sample = 1
                while (boundsOpts.outWidth / sample > 512 || boundsOpts.outHeight / sample > 512) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun getCurrentState(): MediaState {
        val c = currentController
        if (c != null) {
            val meta = c.metadata
            val state = c.playbackState?.state ?: PlaybackState.STATE_NONE
            val isPlaying = state == PlaybackState.STATE_PLAYING ||
                    state == PlaybackState.STATE_BUFFERING ||
                    state == PlaybackState.STATE_FAST_FORWARDING ||
                    state == PlaybackState.STATE_REWINDING ||
                    (isAudioActive && state != PlaybackState.STATE_PAUSED && state != PlaybackState.STATE_STOPPED)

            val title = usable(meta?.getString(MediaMetadata.METADATA_KEY_TITLE))
                ?: usable(meta?.description?.title?.toString())
                ?: if (isPlaying) appLabel(c.packageName) else null

            val artist = usable(meta?.getString(MediaMetadata.METADATA_KEY_ARTIST))
                ?: usable(meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
                ?: usable(meta?.description?.subtitle?.toString())
                ?: usable(meta?.getString(MediaMetadata.METADATA_KEY_ALBUM))
                ?: if (isPlaying) "Playing" else null

            val canSkip = (c.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SKIP_TO_NEXT != 0L

            if (title != null || isPlaying) {
                return MediaState(
                    title = title ?: appLabel(c.packageName),
                    artist = artist,
                    album = usable(meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)),
                    art = currentLoadedArt,
                    isPlaying = isPlaying,
                    canSkip = canSkip,
                    packageName = c.packageName,
                    hasActiveSession = true
                )
            }
        }

        // Notification fallback
        val notif = notificationMedia
        if (notif != null && (notif.title != null || notif.isPlaying)) {
            return MediaState(
                title = notif.title ?: appLabel(notif.packageName),
                artist = notif.artist ?: if (notif.isPlaying) "Playing" else null,
                album = notif.album,
                art = notif.art,
                isPlaying = notif.isPlaying || isAudioActive,
                canSkip = notif.nextIntent != null,
                packageName = notif.packageName,
                hasActiveSession = true
            )
        }

        // Broadcast fallback (AVRCP / Bluetooth / Radio)
        if (broadcastTitle != null && (broadcastPlaying || System.currentTimeMillis() - lastBroadcastTime < 300_000)) {
            return MediaState(
                title = broadcastTitle,
                artist = broadcastArtist ?: if (broadcastPlaying) "Playing" else null,
                album = broadcastAlbum,
                art = null,
                isPlaying = broadcastPlaying || isAudioActive,
                canSkip = true,
                packageName = broadcastPackage,
                hasActiveSession = true
            )
        }

        return MediaState()
    }

    private fun notifyListeners() {
        val state = getCurrentState()
        mainHandler.post {
            for (listener in listeners.toList()) {
                listener.onMediaChanged(state)
            }
        }
    }

    fun playPause() {
        val c = currentController
        if (c != null) {
            val state = c.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
                c.transportControls.pause()
            } else {
                c.transportControls.play()
            }
            return
        }

        val notif = notificationMedia
        if (notif?.playPauseIntent != null) {
            try {
                notif.playPauseIntent.send()
                return
            } catch (t: Throwable) {}
        }

        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    fun skipNext() {
        val c = currentController
        if (c != null) {
            c.transportControls.skipToNext()
            return
        }

        val notif = notificationMedia
        if (notif?.nextIntent != null) {
            try {
                notif.nextIntent.send()
                return
            } catch (t: Throwable) {}
        }

        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun skipPrev() {
        val c = currentController
        if (c != null) {
            c.transportControls.skipToPrevious()
            return
        }

        val notif = notificationMedia
        if (notif?.prevIntent != null) {
            try {
                notif.prevIntent.send()
                return
            } catch (t: Throwable) {}
        }

        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val eventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            am.dispatchMediaKeyEvent(eventDown)
            am.dispatchMediaKeyEvent(eventUp)
        } catch (t: Throwable) {}
    }

    fun launchMediaApp(context: Context) {
        val state = getCurrentState()
        val pkg = state.packageName
        if (!pkg.isNullOrBlank()) {
            context.packageManager.getLaunchIntentForPackage(pkg)?.let {
                context.startActivity(it)
                return
            }
        }

        // Try slot with music or radio
        val dock = Prefs.dock(context)
        for (slot in dock) {
            if (slot.component.isNotEmpty() && (slot.label.contains("Music", true) || slot.label.contains("Radio", true))) {
                val p = ComponentName.unflattenFromString(slot.component)?.packageName ?: slot.component
                context.packageManager.getLaunchIntentForPackage(p)?.let {
                    context.startActivity(it)
                    return
                }
            }
        }

        // Try vendor default
        val profile = VendorProfiles.detect(context.packageManager)
        profile.resolved[VendorProfiles.Role.MUSIC]?.let { comp ->
            val p = ComponentName.unflattenFromString(comp)?.packageName ?: comp
            context.packageManager.getLaunchIntentForPackage(p)?.let {
                context.startActivity(it)
                return
            }
        }

        // Intent with CATEGORY_APP_MUSIC
        val musicIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
        if (musicIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(musicIntent)
            return
        }

        // Any audio player
        val installed = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///dummy.mp3"), "audio/*"),
            0
        )
        for (resolve in installed) {
            val p = resolve.activityInfo.packageName
            if (p != context.packageName) {
                context.packageManager.getLaunchIntentForPackage(p)?.let {
                    context.startActivity(it)
                    return
                }
            }
        }
    }

    private fun usable(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.equals("unknown", true) || trimmed == "<unknown>") null else trimmed
    }

    private fun appLabel(pkg: String): String {
        return try {
            val pm = appContext?.packageManager ?: return pkg
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Throwable) {
            pkg
        }
    }
}
