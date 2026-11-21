package com.debashis.carlauncher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Bridges system notifications and media session tokens to MediaManager.
 *
 * Android 8.1+ requires a NotificationListenerService to access MediaSessionManager.
 * In addition, this service captures MediaSession.Tokens and media playback notifications
 * directly from apps (like browsers, video players, and headunit apps).
 */
class DashNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaManager.onNotificationListenerConnected(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        MediaManager.onNotificationListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { MediaManager.onNotificationPosted(this, it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn?.let { MediaManager.onNotificationRemoved(it) }
    }
}
