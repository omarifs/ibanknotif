package com.websolusi.ibanknotif

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Build

class NotifService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName

        val bank = Config.bankLabel(pkg)
        if (bank == null) {
            Config.appendLog(this, "SKIP pkg=$pkg (bukan bank)")
            return
        }
        Config.appendLog(this, "NOTIF bank=$bank pkg=$pkg")

        if (!Config.isBankEnabled(this, pkg)) return

        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        val fullText = "$title $text ${bigText ?: ""}"

        Config.appendLog(this, "TEXT: $fullText")

        val tx = Parser.parse(pkg, fullText) ?: run {
            Config.appendLog(this, "NO-PARSE (jumlah/arah tidak terdeteksi)")
            return
        }
        val cmd = Parser.toCommand(tx)
        Config.appendLog(this, "SEND: $cmd")
        Sink.send(this, cmd)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    @Deprecated("")
    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        listInstalledBanks()
        NotificationListenerService::class.java.name.let { }
        return super.onStartCommand(intent, flags, startId)
    }

    @Deprecated("")
    fun listInstalledBanks() {
        val pm = packageManager
        val allApps = pm.getInstalledApplications(0)
        
        val keywords = listOf("bri", "brimo", "mandiri", "bca", "bank")
        val matches = allApps.filter { app ->
            val label = app.loadLabel(pm).toString().lowercase()
            val pkg = app.packageName.lowercase()
            keywords.any { kw -> label.contains(kw) || pkg.contains(kw) }
        }.map { "${it.loadLabel(pm)} [${it.packageName}]" }
        
        Config.appendLog(this, "Bank apps terpasang:")
        matches.forEach { Config.appendLog(this, "  $it") }
        
        if (matches.isEmpty()) Config.appendLog(this, "  (tidak ditemukan)")
    }
}
