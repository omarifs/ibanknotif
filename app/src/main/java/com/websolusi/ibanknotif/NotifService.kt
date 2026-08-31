package com.websolusi.ibanknotif

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Build

class NotifService : NotificationListenerService() {

    private val TITLE_BANK = mapOf(
        "brimo" to "bri",
        "bri" to "bri",
        "bca" to "bca",
        "livin" to "mandiri",
        "mandiri" to "mandiri"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""

        val bank = Config.bankLabel(pkg) ?: detectBankFromTitle(title)
        if (bank == null) {
            Config.appendLog(this, "SKIP pkg=$pkg title=$title (bukan bank)")
            return
        }
        Config.appendLog(this, "NOTIF bank=$bank pkg=$pkg")

        if (!Config.isBankEnabledByLabel(bank)) return

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        val fullText = "$title $text ${bigText ?: ""}"

        Config.appendLog(this, "TEXT: $fullText")

        val tx = Parser.parseByBank(bank, fullText) ?: run {
            Config.appendLog(this, "NO-PARSE (jumlah/arah tidak terdeteksi)")
            return
        }
        val cmd = Parser.toCommand(tx)
        Config.appendLog(this, "SEND: $cmd")
        Sink.send(this, cmd)
    }

    private fun detectBankFromTitle(title: String): String? {
        val lower = title.lowercase()
        for ((key, label) in TITLE_BANK) {
            if (lower.contains(key)) return label
        }
        return null
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

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
