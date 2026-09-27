package com.websolusi.ibanknotif

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var etEndpoint: TextInputEditText
    private lateinit var etChatId: TextInputEditText
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etCustomPrompt: TextInputEditText
    private lateinit var cbBca: MaterialCheckBox
    private lateinit var cbMandiri: MaterialCheckBox
    private lateinit var cbBri: MaterialCheckBox
    private lateinit var tvLog: android.widget.TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etEndpoint = findViewById(R.id.etEndpoint)
        etChatId = findViewById(R.id.etChatId)
        etApiKey = findViewById(R.id.etApiKey)
        etCustomPrompt = findViewById(R.id.etCustomPrompt)
        cbBca = findViewById(R.id.cbBca)
        cbMandiri = findViewById(R.id.cbMandiri)
        cbBri = findViewById(R.id.cbBri)
        tvLog = findViewById(R.id.tvLog)

        loadConfig()
        if (isNotifServiceEnabled() && !NotifService.ready()) requestListenerBind()

        findViewById<MaterialButton>(R.id.btnNotifAccess).setOnClickListener {
            if (!isNotifServiceEnabled()) {
                AlertDialog.Builder(this)
                    .setTitle("Akses Notifikasi Diperlukan")
                    .setMessage("Aktifkan akses notifikasi untuk IBankNotif agar bisa membaca notifikasi bank secara otomatis.")
                    .setPositiveButton("Buka Pengaturan") { _, _ ->
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            } else {
                Toast.makeText(this, "Akses Notifikasi Sudah Aktif", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            saveConfig()
            Toast.makeText(this, "Pengaturan tersimpan", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnTrigger).setOnClickListener {
            saveConfig()
            withService { service ->
                Config.appendLog(this, "--- PEMINDAIAN MANUAL NOTIFIKASI AKTIF ---")
                val count = service.scanActiveNotifications()
                Toast.makeText(this, "Memindai $count notifikasi aktif di HP...", Toast.LENGTH_SHORT).show()
                tvLog.postDelayed({ tvLog.text = Config.getLog(this) }, 1500)
            }
        }

        findViewById<MaterialButton>(R.id.btnScanAi).setOnClickListener {
            saveConfig()
            withService { service ->
                val key = Learning.apiKey(this)
                if (key.isBlank()) {
                    Toast.makeText(this, "Isi Gemini API Key dulu", Toast.LENGTH_LONG).show()
                    return@withService
                }
                val active = service.activeNotifications ?: emptyArray()
                if (active.isEmpty()) {
                    Toast.makeText(this, "Tidak ada notifikasi aktif untuk dipindai", Toast.LENGTH_LONG).show()
                    return@withService
                }
                // (bank, RawNotif, notifKey)
                val notifs = active.mapNotNull { sbn ->
                    val pkg = sbn.packageName
                    val bank = Config.bankLabel(pkg) ?: return@mapNotNull null
                    val ex = sbn.notification?.extras ?: return@mapNotNull null
                    val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
                    val text = ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
                    val big = ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
                    val full = "$title $text ${big ?: ""}"
                    Triple(bank, Gemini.RawNotif(pkg, title, text, big), Sink.dedupKey(bank, pkg, sbn.postTime, full))
                }
                if (notifs.isEmpty()) {
                    Toast.makeText(this, "Tidak ada notifikasi bank yang cocok", Toast.LENGTH_LONG).show()
                    return@withService
                }
                Toast.makeText(this, "Menganalisis ${notifs.size} notif...", Toast.LENGTH_SHORT).show()
                Thread {
                    val customPrompt = Learning.customPrompt(this)
                    val rules = RuleStore.load(this)
                    val res = arrayOfNulls<AiResult>(notifs.size)
                    val aiIdx = ArrayList<Int>()
                    val aiNotifs = ArrayList<Gemini.RawNotif>()

                    // 1. Aturan dulu: gratis, offline, hasilnya bisa ditinjau.
                    notifs.forEachIndexed { i, (bank, n, _) ->
                        val full = "${n.title} ${n.text} ${n.bigText ?: ""}"
                        val parsed = Parser.parseByBank(bank, full) ?: return@forEachIndexed
                        val d = RuleEngine.decide(parsed, rules)
                        when {
                            d.outcome != null -> res[i] = AiResult(
                                bank = bank, amount = parsed.amount, direction = d.outcome.direction,
                                last4 = Parser.extractLast4(full), ewallet = null,
                                command = d.outcome.command, pattern = ""
                            )
                            // Aturan stop adalah keputusan sadar, jangan ditanya AI.
                            d.stoppedBy != null -> Unit
                            else -> { aiIdx.add(i); aiNotifs.add(n) }
                        }
                    }

                    // 2. Sisanya saja yang dikirim ke Gemini.
                    if (aiNotifs.isNotEmpty()) {
                        val gres = Gemini.extract(this, key, aiNotifs, customPrompt)
                        gres.forEachIndexed { k, r ->
                            val i = aiIdx[k]
                            if (r != null && RuleEngine.isSendable(r.command)) res[i] = r
                        }
                    }
                    val valid = res.filterNotNull().filter { it.command.isNotBlank() }
                    if (valid.isEmpty()) {
                        runOnUiThread {
                            Config.appendLog(this, "AI: tidak ada transaksi terdeteksi")
                            tvLog.text = Config.getLog(this)
                            Toast.makeText(this, "AI tidak menemukan transaksi", Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    // terpusat di Pindai: auto-save learning + auto-kirim
                    Learning.addTemplates(this, valid)
                    var sent = 0
                    var skip = 0
                    res.forEachIndexed { i, r ->
                        if (r == null || r.command.isBlank()) return@forEachIndexed
                        val keyNotif = notifs[i].third
                        if (Config.isProcessed(this, keyNotif)) {
                            skip++
                            return@forEachIndexed
                        }
                        // Anti catat ganda per transaksi: satu transfer bisa muncul
                        // sebagai dua notif di shade, jangan jadi /pindahin + /tabout.
                        val (bank, n, _) = notifs[i]
                        val full = "${n.title} ${n.text} ${n.bigText ?: ""}"
                        val txSig = Parser.transactionSignature(bank, full, r.amount)
                        if (txSig != null && Config.isProcessed(this, txSig)) {
                            skip++
                            return@forEachIndexed
                        }
                        Sink.send(this, r.command, keyNotif)
                        Config.markProcessed(this, keyNotif, *listOfNotNull(txSig).toTypedArray())
                        sent++
                    }
                    runOnUiThread {
                        Config.appendLog(this, "AI DETEKSI ${valid.size} transaksi, kirim $sent, skip(dub) $skip")
                        val summary = valid.joinToString("\n\n") { it.render() }
                        tvLog.text = Config.getLog(this)
                        AlertDialog.Builder(this)
                            .setTitle("Hasil Pindai AI")
                            .setMessage("$summary\n\nTemplate belajar otomatis tersimpan & ${sent} command dikirim ke Telegram.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }.start()
            }
        }

        findViewById<MaterialButton>(R.id.btnRules).setOnClickListener {
            showRulesDialog()
        }

        findViewById<MaterialButton>(R.id.btnFixAi).setOnClickListener {
            saveConfig()
            askAiForRule()
        }

        findViewById<MaterialButton>(R.id.btnClearLearn).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Bersihkan Hasil Belajar")
                .setMessage("Hapus semua template hasil belajar AI? Auto-detect akan pakai regex bawaan.")
                .setPositiveButton("Hapus") { _, _ ->
                    Learning.clear(this)
                    Config.appendLog(this, "Template belajar dihapus")
                    Toast.makeText(this, "Template belajar dihapus", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        tvLog.text = Config.getLog(this)
        if (isNotifServiceEnabled() && !NotifService.ready()) requestListenerBind()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun loadConfig() {
        etEndpoint.setText(Config.endpoint(this))
        etChatId.setText(Config.chatId(this).toString())
        etApiKey.setText(Learning.apiKey(this))
        etCustomPrompt.setText(Learning.customPrompt(this))
        cbBca.isChecked = true
        cbMandiri.isChecked = true
        cbBri.isChecked = true
    }

    private fun saveConfig() {
        val endpoint = etEndpoint.text?.toString()?.trim() ?: ""
        val chatId = etChatId.text?.toString()?.trim()?.toLongOrNull() ?: -1002031892399
        Config.save(this, endpoint, chatId, cbBca.isChecked, cbMandiri.isChecked, cbBri.isChecked)
        Learning.saveApiKey(this, etApiKey.text?.toString() ?: "")
        Learning.saveCustomPrompt(this, etCustomPrompt.text?.toString() ?: "")
    }

    // ---- Layar Aturan ----

    private fun showRulesDialog(seedJson: String? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_rules, null)
        val tvInfo = view.findViewById<android.widget.TextView>(R.id.tvRulesInfo)
        val etJson = view.findViewById<TextInputEditText>(R.id.etRulesJson)
        val tvError = view.findViewById<android.widget.TextView>(R.id.tvRulesError)
        val tvDry = view.findViewById<android.widget.TextView>(R.id.tvDryRun)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Aturan Klasifikasi")
            .setView(view)
            .setPositiveButton("Simpan", null)
            .setNeutralButton("Bawaan", null)
            .setNegativeButton("Tutup", null)
            .create()

        fun refreshInfo() {
            val set = runCatching { RuleSet.fromJson(etJson.text?.toString() ?: "") }.getOrNull()
            val asal = if (RuleStore.isOverridden(this)) "diperbaiki manual" else "bawaan aplikasi"
            tvInfo.text = if (set == null) {
                "JSON tidak bisa dibaca. Sumber: $asal"
            } else {
                "versi ${set.version} - $asal - ${set.rules.size} aturan\n" +
                    "rekening: " + set.accounts.entries.joinToString(", ") { "${it.key}=${it.value}" }
            }
        }

        fun dryRun() {
            val set = runCatching { RuleSet.fromJson(etJson.text?.toString() ?: "") }.getOrNull()
            if (set == null) {
                tvDry.visibility = android.view.View.GONE
                return
            }
            val lines = ArrayList<String>()
            for (n in bankSamples()) {
                val bank = Config.bankLabel(n.pkg) ?: continue
                val parsed = Parser.parseByBank(bank, fullText(n)) ?: continue
                lines.add(RuleEngine.explain(parsed, set))
            }
            if (lines.isEmpty()) {
                lines.add("Tidak ada notifikasi bank aktif untuk diuji.")
            }
            tvDry.text = "UJI COBA (" + lines.size + " notif):\n\n" + lines.joinToString("\n\n")
            tvDry.visibility = android.view.View.VISIBLE
        }

        etJson.setText(seedJson ?: RuleSet.toJson(RuleStore.load(this)))
        refreshInfo()
        dryRun()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val set = RuleSet.fromJson(etJson.text?.toString() ?: "")
                if (set == null) {
                    tvError.text = "JSON tidak bisa dibaca."
                    tvError.visibility = android.view.View.VISIBLE
                    return@setOnClickListener
                }
                val err = RuleStore.save(this, set)
                if (err != null) {
                    tvError.text = err
                    tvError.visibility = android.view.View.VISIBLE
                    return@setOnClickListener
                }
                tvError.visibility = android.view.View.GONE
                Toast.makeText(this, "Aturan disimpan", Toast.LENGTH_SHORT).show()
                refreshInfo()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val hist = RuleStore.history(this)
                if (hist.isEmpty()) {
                    RuleStore.reset(this)
                    etJson.setText(RuleSet.toJson(RuleStore.load(this)))
                    refreshInfo()
                    Toast.makeText(this, "Dikembalikan ke aturan bawaan", Toast.LENGTH_SHORT).show()
                } else {
                    val labels = hist.mapIndexed { i, h -> "${i + 1}. versi ${h.version} (${h.rules.size} aturan)" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Kembalikan versi lama")
                        .setItems(labels) { _, i ->
                            RuleStore.rollback(this, hist[i])
                            etJson.setText(RuleSet.toJson(RuleStore.load(this)))
                            refreshInfo()
                            Toast.makeText(this, "Dikembalikan ke versi ${hist[i].version}", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                }
            }
        }
        dialog.show()

        // Tarik dari server. Sengaja lewat tombol, bukan otomatis: tarikan
        // otomatis akan menimpa aturan yang sudah diedit manual di HP ini.
        view.findViewById<MaterialButton>(R.id.btnRulesSync).setOnClickListener {
            it.isEnabled = false
            Thread {
                val msg = RuleStore.fetchFromServer(this)
                runOnUiThread {
                    it.isEnabled = true
                    Toast.makeText(this, msg ?: "Tidak ada perubahan dari server", Toast.LENGTH_LONG).show()
                    if (msg != null && msg.startsWith("Aturan diperbarui")) {
                        etJson.setText(RuleSet.toJson(RuleStore.load(this)))
                        refreshInfo()
                        dryRun()
                    }
                }
            }
        }
    }

    /** Notifikasi bank yang masih aktif di shade. */
    private fun bankSamples(): List<Gemini.RawNotif> {
        val svc = NotifService.instance ?: return emptyList()
        val active = runCatching { svc.activeNotifications }.getOrNull() ?: return emptyList()
        return active.mapNotNull { sbn ->
            if (Config.bankLabel(sbn.packageName) == null) return@mapNotNull null
            val ex = sbn.notification?.extras ?: return@mapNotNull null
            Gemini.RawNotif(
                pkg = sbn.packageName,
                title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: "",
                text = ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: "",
                bigText = ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
            )
        }
    }

    private fun fullText(n: Gemini.RawNotif): String = "${n.title} ${n.text} ${n.bigText ?: ""}"

    /**
     * Minta Gemini proposes satu aturan baru untuk notifikasi bank yang aktif.
     * Hasilnya tidak pernah langsung disimpan: keluarannya ditampilkan dulu,
     * dan hanya ikut ke editor Aturan untuk ditinjau dan disimpan manual.
     */
    private fun askAiForRule() {
        val key = Learning.apiKey(this)
        if (key.isBlank()) {
            Toast.makeText(this, "Isi Gemini API Key dulu", Toast.LENGTH_LONG).show()
            return
        }
        val samples = bankSamples()
        if (samples.isEmpty()) {
            Toast.makeText(this, "Tidak ada notifikasi bank aktif", Toast.LENGTH_LONG).show()
            return
        }
        val parsed = samples.mapNotNull { n ->
            Parser.parseByBank(Config.bankLabel(n.pkg) ?: return@mapNotNull null, fullText(n))
        }
        if (parsed.isEmpty()) {
            Toast.makeText(this, "Tidak ada notifikasi yang berisi nominal", Toast.LENGTH_LONG).show()
            return
        }
        val rules = RuleStore.load(this)
        val outcome = parsed.joinToString("\n\n") { RuleEngine.explain(it, rules) }

        Toast.makeText(this, "Gemini menganalisis ${parsed.size} notif...", Toast.LENGTH_SHORT).show()
        Thread {
            val res = Gemini.suggestRule(this, key, samples, rules, outcome, Learning.customPrompt(this))
            runOnUiThread {
                if (res.error != null) {
                    AlertDialog.Builder(this)
                        .setTitle("Usulan AI ditolak")
                        .setMessage(res.error + "\n\nTidak ada yang diubah. Aturan tetap seperti sebelumnya.")
                        .setPositiveButton("Tutup", null)
                        .show()
                    return@runOnUiThread
                }
                val rule = res.rule ?: return@runOnUiThread
                val candidate = RuleSet(
                    rules.version,
                    rules.accounts,
                    rules.rules.filter { it.id != rule.id } + rule
                )
                val json = RuleSet.toJson(candidate)
                val d = AlertDialog.Builder(this)
                    .setTitle("Usulan aturan AI")
                    .setMessage(
                        "id      : ${rule.id}\n" +
                            "nama    : ${rule.name}\n" +
                            "bank    : ${rule.bank}   priority: ${rule.priority}\n" +
                            "when    : ${rule.when.joinToString(" & ")}\n" +
                            "command : ${if (rule.stop) "diam" else rule.command}\n\n" +
                            "Usulan ini belum dipakai. Simpan lewat tombol Aturan Klasifikasi."
                    )
                    .setPositiveButton("Buka di editor Aturan") { _, _ ->
                        showRulesDialog(seedJson = json)
                    }
                    .setNegativeButton("Buang", null)
                    .create()
                d.show()
            }
        }.start()
    }

    private fun isNotifServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == pkgName) return true
            }
        }
        return false
    }

    private fun withService(action: (NotifService) -> Unit) {
        if (!isNotifServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Akses Notifikasi Mati")
                .setMessage("Izin akses notifikasi belum aktif. Aktifkan IBankNotif di pengaturan izin notifikasi, lalu kembali ke sini.")
                .setPositiveButton("Buka Pengaturan") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }
        val ready = NotifService.instance
        if (NotifService.ready() && ready != null) {
            action(ready)
            return
        }
        requestListenerBind()
        Toast.makeText(this, "Service sedang diaktifkan...", Toast.LENGTH_SHORT).show()
        Config.appendLog(this, "SERVICE REBIND diminta (izin OK, belum connected)")
        val delays = longArrayOf(1000, 2000, 4000)
        fun retry(index: Int) {
            handler.postDelayed({
                val service = NotifService.instance
                if (NotifService.ready() && service != null) action(service)
                else if (index < delays.lastIndex) retry(index + 1)
                else showServiceStuckDialog()
            }, delays[index])
        }
        retry(0)
    }

    private fun requestListenerBind() {
        NotificationListenerService.requestRebind(ComponentName(this, NotifService::class.java))
    }

    private fun showServiceStuckDialog() {
        Config.appendLog(this, "SERVICE STUCK: instance null setelah retry")
        AlertDialog.Builder(this)
            .setTitle("Service belum aktif")
            .setMessage("MIUI menahan listener. Matikan batasan baterai IBankNotif, aktifkan Autostart, lalu buka ulang akses notifikasi.")
            .setPositiveButton("Akses notifikasi") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNeutralButton("Baterai") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }
}
