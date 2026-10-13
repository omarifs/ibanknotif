package com.websolusi.ibanknotif

import android.app.Notification
import android.os.Handler
import android.os.Looper
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

    /** Penjadwalan pembuangan sisi [Pairer] yang sudah lewat jendela. */
    private val flushHandler = Handler(Looper.getMainLooper())

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        connected = true
        Config.appendLog(this, "SERVICE CONNECTED")
        // Command yang sudah diputuskan tapi belum tentu terkirim sebelum proses
        // mati. Outbox ini yang membuatnya tidak hilang, dan [Pairer] yang
        // menahannya juga ada di sini kalau jendela PAIR masih menyala.
        flushHandler.postDelayed(flushRunnable, 2000L)
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
            submit(learned, dedupKey, postTime, rules.pairs, newKeys)
            return
        }

        // 2. Aturan. Ini penentu utama, dan datanya bisa diperbaiki dari dalam
        //    aplikasi tanpa build ulang.
        val parsed = Parser.parseByBank(bank, fullText) ?: run {
            Config.appendLog(this, "SKIP (TIDAK ADA NOMINAL): $fullText")
            return
        }
        val decision = RuleEngine.decide(parsed, rules, Config.deviceAccount(this))
        if (decision.outcome == null) {
            val alasan = if (decision.stoppedBy != null) "diam: ${decision.stoppedBy.name}" else "tidak ada aturan yang cocok"
            Config.appendLog(this, "SKIP ($alasan): $fullText")
            return
        }
        val out = decision.outcome
        // Dicatat di sini, bukan di dalam submit(), karena nama aturannya hanya
        // ada pada jalur ini. Jalur template belajar sudah punya barisnya sendiri.
        Config.appendLog(this, "AUTO-SEND [${out.ruleId}]: ${out.command}")
        submit(out.command, dedupKey, postTime, rules.pairs, newKeys)
    }

    /**
     * Titik temu semua hasil klasifikasi, baik dari template belajar maupun dari
     * aturan. Keduanya sudah lolos [RuleEngine.isSendable] sebelum sampai sini,
     * jadi id yang dikirim selalu terdaftar.
     *
     * Urutannya penting dan berbalik dari versi lama. Dulu notifikasi ditandai
     * "sudah diproses" **sebelum** dikirim. Kalau proses mati di antara keduanya,
     * notifikasi itu hilang permanen: `isProcessed` menyingkirkannya di setiap
     * catch-up berikutnya, dan `dedup_key`-nya sudah diklaim di server sehingga
     * kirim ulang juga ditolak. Itulah yang membuat satu transfer Gopay 69.350
     * hilang tanpa jejak.
     *
     * Sekarang urutannya: **tulis ke outbox dulu**, baru tandai diproses,
     * baru kirim. Yang masih ada di outbox dikirim ulang setiap aplikasi dibuka.
     * [newKeys] tidak boleh ditandai sebelum outbox aman - itu satu-satunya
     *_barang_ yang menahan notifikasi agar tidak diproses dua kali.
     */
    private fun submit(
        command: String,
        dedupKey: String?,
        postTime: Long,
        cfg: PairConfig,
        newKeys: Array<String>
    ) {
        Config.enqueueOutbox(this, Config.Outbox(command, dedupKey, postTime))
        Config.markProcessed(this, *newKeys)

        if (!cfg.enabled) {
            kirim(command, dedupKey)
            return
        }
        val buffer = Config.loadPending(this)
        when (val outcome = Pairer.feed(command, dedupKey, postTime, cfg, buffer)) {
            is Pairer.Outcome.Pass -> kirim(outcome.command, outcome.dedupKey)
            is Pairer.Outcome.Held -> {
                Config.savePending(this, buffer)
                Config.appendLog(this, "TAHAN (menunggu sisi lain): ${outcome.pending.command}")
                scheduleFlush(cfg)
            }
            is Pairer.Outcome.Paired -> {
                Config.savePending(this, buffer)
                Config.appendLog(
                    this,
                    "PASANG: ${outcome.out.command} + ${outcome.inSide.command} -> ${outcome.command}"
                )
                // Kunci sendiri, bukan milik salah satu sisi: kunci sisi akan
                // dipakai lagi kalau notifikasi yang sama muncul sekali lagi.
                kirim(
                    outcome.command,
                    Sink.pairKey(outcome.out.dedupKey, outcome.inSide.dedupKey)
                )
            }
        }
    }

    /**
     * Kirim satu command dan buang dari outbox setelah unmistakably diterima.
     *
     * Sisa di outbox sengaja tidak dibuang di sini: [Sink] bekerja di thread
     * sendiri, jadi "sudah dipanggil" bukan "sudah sampai". Pembuangan terjadi
     * setelah jeda secukupnya lewat [flushRunnable], yang juga mengirim ulang
     * yang masih tertinggal. Kalau proses mati sebelum itu, outbox masih
     * menyimpan command dan dikirim ulang saat aplikasi dibuka.
     */
    private fun kirim(command: String, dedupKey: String?) {
        Sink.send(this, command, dedupKey)
        flushHandler.removeCallbacks(flushRunnable)
        flushHandler.postDelayed(flushRunnable, 8000L)
    }

    /**
     * Jadwalkan pembuangan sisi yang sudah lewat jendela.
     *
     * Ini bukan sekadar tambahan: tanpa penjadwalan, sisi `tabin` yang sendirian
     * (dana masuk dari orang luar) tertahan selamanya kalau tidak ada notifikasi
     * lain yang datang, dan uangnya tidak pernah tercatat.
     */
    private fun scheduleFlush(cfg: PairConfig) {
        flushHandler.removeCallbacks(flushRunnable)
        flushHandler.postDelayed(flushRunnable, cfg.windowMs + 1000L)
    }

    private val flushRunnable = Runnable { flushExpired() }

    /**
     * Dua pekerjaan, berurutan.
     *
     * **Outbox.** Yang masih tertinggal dikirim ulang, lalu dibuang. Pembuangan
     * dilakukan setelah pemanggilan kirim, dengan asumsi respons sudah sampai
     * dalam jeda ini. Yang benar-benar gagal akan terlihat di log sebagai
     * `GAGAL kirim`, dan masih ada di outbox sampai pengiriman berikutnya -
     * jadi yang gagal tidak hilang diam-diam.
     *
     * **Pairer.** Sisi yang ditahan tapi tidak pernah berpasangan dikirim apa
     * adanya setelah jendela habis, supaya tidak menggantung selamanya.
     */
    private fun flushExpired() {
        val outbox = Config.loadOutbox(this)
        if (outbox.isNotEmpty()) {
            outbox.forEach { item ->
                Config.appendLog(this, "KIRIM ULANG (outbox): ${item.command}")
                Sink.send(this, item.command, item.dedupKey)
            }
            Config.saveOutbox(this, emptyList())
        }

        val cfg = RuleStore.load(this).pairs
        if (!cfg.enabled) return
        val buffer = Config.loadPending(this)
        val stale = Pairer.flushExpired(buffer, System.currentTimeMillis(), cfg)
        if (stale.isEmpty()) return
        buffer.removeAll(stale.toSet())
        Config.savePending(this, buffer)
        for (p in stale) {
            Config.appendLog(this, "FLUSH (tidak berpasangan): ${p.command}")
            kirim(p.command, p.dedupKey)
        }
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
