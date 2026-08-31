package com.websolusi.ibanknotif

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Build

class NotifService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName

        if (!Config.isBankEnabled(this, pkg)) return

        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        val fullText = "$title $text ${bigText ?: ""}"

        val tx = Parser.parse(pkg, fullText) ?: return
        val cmd = Parser.toCommand(tx)
        Sink.send(this, cmd)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}
}
