package com.websolusi.ibanknotif

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifService : NotificationListenerService() {

    private val TITLE_BANK = mapOf(
        "brimo" to "bri",
        "bri" to "bri",
        "bca" to "bca",
        "mybca" to "bca",
        "financial diary" to "bca",
        "livin" to "mandiri",
        "mandiri" to "mandiri"
    )

    companion object {
        @Volatile var instance: NotifService? = null
        fun ready(): Boolean = instance?.connected == true
    }

    @Volatile private var connected = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        connected = true
        Config.appendLog(this, "SERVICE CONNECTED")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        if (instance == this) instance = null
        Config.appendLog(this, "SERVICE DISCONNECTED")
    }

    override fun onDestroy() {
        super.onDestroy()
        connected = false
        if (instance == this) instance = null
    }

    fun scanActiveNotifications(): Int {
        if (!connected) return 0
        val activeNotifs = try { activeNotifications } catch (_: Exception) { return 0 } ?: return 0
        var processed = 0
        for (sbn in activeNotifs) {
            onNotificationPosted(sbn)
            processed++
        }
        return processed
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return // Ignore own notifications

        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""

        val bank = Config.bankLabel(pkg) ?: detectBankFromTitle(title) ?: return

        if (!Config.isBankEnabledByLabel(bank)) return

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        val fullText = "$title $text ${bigText ?: ""}"
        val postTime = sbn.postTime
        val notifKey = "${pkg}_${postTime}_${fullText.hashCode()}"

        if (Config.isProcessed(this, notifKey)) {
            Config.appendLog(this, "SKIP (DUPLIKAT): $fullText")
            return
        }

        Config.appendLog(this, "AUTODETECT [$bank]: $fullText")

        // 1. coba template hasil belajar AI (prediksi paling akurat)
        var learnedCmd = Learning.match(bank, fullText, this)
        if (learnedCmd != null && learnedCmd.contains("{amount}")) {
            val amt = Parser.amountOf(fullText) ?: 0
            learnedCmd = Learning.fillAmount(learnedCmd, amt)
        }
        if (learnedCmd != null && !learnedCmd.contains("{amount}")) {
            Config.appendLog(this, "AUTO-SEND (LEARNED): $learnedCmd")
            Config.markProcessed(this, notifKey)
            Sink.send(this, learnedCmd)
            return
        }

        // 2. fallback ke parser regex statis
        val tx = Parser.parseByBank(bank, fullText) ?: run {
            Config.appendLog(this, "SKIP (PARSE GAGAL): $fullText")
            return
        }
        val cmd = Parser.toCommand(tx)
        Config.appendLog(this, "AUTO-SEND: $cmd")
        Config.markProcessed(this, notifKey)
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
}
