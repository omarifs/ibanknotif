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
        // Catch-up: proses notif bank aktif yang tertinggal saat service suspend.
        Thread {
            val n = scanActiveNotifications()
            if (n > 0) Config.appendLog(this, "CATCH-UP (reconnect): $n notif diproses")
        }.start()
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
        val dedupKey = Sink.dedupKey(bank, pkg, postTime, fullText)

        if (Config.isProcessed(this, notifKey)) {
            Config.appendLog(this, "SKIP (DUPLIKAT): $fullText")
            return
        }

        // Anti catat ganda per transaksi, dicek SEBELUM jalur apa pun: dua notif BRI
        // untuk satu transfer memakai waktu event yang sama, jadi yang kedua harus
        // tertahan. Ini yang mencegah /pindahin dan /tabout tercatat untuk 1 transfer.
        val amount = Parser.amountOf(fullText)
        val txSig = if (amount != null) Parser.transactionSignature(bank, fullText, amount) else null
        val newKeys = arrayOf(notifKey) + listOfNotNull(txSig).toTypedArray()
        if (txSig != null && Config.isProcessed(this, txSig)) {
            Config.appendLog(this, "SKIP (TRANSAKSI SAMA SUDAH DICATAT): $fullText")
            return
        }

        Config.appendLog(this, "AUTODETECT [$bank]: $fullText")

        // 1. Template hasil belajar (pola dari Gemini) jadi cadangan klasifikasi
        //    yang sudah dikoreksi manual sebelumnya. Dievaluasi sebelum rules,
        //    jadi gerbang isSendable di sini wajib memakai knownIds yang sama:
        //    kalau tidak, template lama bisa mengirim id akun yang tak terdaftar.
        val rules = RuleStore.load(this)
        val learned = Learning.match(bank, fullText, this)?.let { cmd ->
            if (cmd.contains("{amount}")) Learning.fillAmount(cmd, Parser.amountOf(fullText) ?: 0L) else cmd
        }
        if (learned != null && !learned.contains("{amount}") && RuleEngine.isSendable(learned, rules.knownIds)) {
            Config.appendLog(this, "AUTO-SEND (LEARNED): $learned")
            Config.markProcessed(this, *newKeys)
            Sink.send(this, learned, dedupKey)
            return
        }

        // 2. Aturan. Ini penentu utama, dan datanya bisa diperbaiki dari dalam
        //    aplikasi tanpa build ulang.
        val parsed = Parser.parseByBank(bank, fullText) ?: run {
            Config.appendLog(this, "SKIP (TIDAK ADA NOMINAL): $fullText")
            return
        }
        val decision = RuleEngine.decide(parsed, rules)
        if (decision.outcome == null) {
            val alasan = if (decision.stoppedBy != null) "diam: ${decision.stoppedBy.name}" else "tidak ada aturan yang cocok"
            Config.appendLog(this, "SKIP ($alasan): $fullText")
            return
        }
        val out = decision.outcome
        Config.appendLog(this, "AUTO-SEND [${out.ruleId}]: ${out.command}")
        Config.markProcessed(this, *newKeys)
        Sink.send(this, out.command, dedupKey)
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
