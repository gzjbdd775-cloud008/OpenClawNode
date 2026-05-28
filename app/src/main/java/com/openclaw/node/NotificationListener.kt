package com.openclaw.node

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Listens for device notifications and makes them available on demand.
 */
class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifListener"
        private var instance: NotificationListener? = null
        private var cachedNotifications = mutableListOf<StatusBarNotification>()

        fun getCachedNotifications(): List<StatusBarNotification> = cachedNotifications.toList()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Notification listener created")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let {
            cachedNotifications.removeAll { n -> n.id == it.id && n.packageName == it.packageName }
            cachedNotifications.add(it)
            if (cachedNotifications.size > 100) {
                cachedNotifications.removeAt(0)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let {
            cachedNotifications.removeAll { n -> n.id == it.id && n.packageName == it.packageName }
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
