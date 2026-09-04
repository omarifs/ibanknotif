package com.websolusi.ibanknotif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

class NotifService : NotificationListenerService() {

    private val CHANNEL_ID = "ibanknotif_service_channel"
    private val NOTIF_ID = 1001

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
        var instance: NotifService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundServiceNotification()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    fun scanActiveNotifications(): Int {
        val activeNotifs = activeNotifications ?: return 0
        var processed = 0
        for (sbn in activeNotifs) {
            onNotificationPosted(sbn)
            processed++
        }
        return processed
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Layanan Pemantau Notifikasi Bank",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IBankNotif Aktif")
            .setContentText("Memantau transaksi otomatis di latar belakang")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIF_ID, notification)
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
