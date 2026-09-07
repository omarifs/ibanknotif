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
    private lateinit var etCustomCmd: TextInputEditText
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
        etCustomCmd = findViewById(R.id.etCustomCmd)
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
                    Triple(bank, Gemini.RawNotif(pkg, title, text, big), "${pkg}_${sbn.postTime}_${full.hashCode()}")
                }
                if (notifs.isEmpty()) {
                    Toast.makeText(this, "Tidak ada notifikasi bank yang cocok", Toast.LENGTH_LONG).show()
                    return@withService
                }
                Toast.makeText(this, "Menganalisis ${notifs.size} notif...", Toast.LENGTH_SHORT).show()
                Thread {
                    val customs = Learning.customCommands(this)
                    val customLines = customs.mapNotNull { c ->
                        listOf(c.bank, c.pattern, c.command).filter { it.isNotBlank() }
                            .joinToString("::").ifBlank { null }
                    }
                    val res = arrayOfNulls<AiResult>(notifs.size)
                    val geminiIdx = ArrayList<Int>()
                    val geminiNotifs = ArrayList<Gemini.RawNotif>()
                    notifs.forEachIndexed { i, (bank, n, _) ->
                        val full = "${n.title} ${n.text} ${n.bigText ?: ""}"
                        var made: AiResult? = null
                        for (c in customs) {
                            if (c.bank.isNotBlank() && c.bank != bank) continue
                            if (c.pattern.isBlank()) continue
                            try {
                                if (Regex(c.pattern, RegexOption.IGNORE_CASE).containsMatchIn(full)) {
                                    val amt = Parser.amountOf(full) ?: 0
                                    val cmd = Learning.fillAmount(c.command, amt)
                                    if (cmd.isNotBlank() && !cmd.contains("{amount}")) {
                                        val dir = when {
                                            cmd.startsWith("/pindahin") -> "pindahin"
                                            cmd.startsWith("/tabin") -> "in"
                                            else -> "out"
                                        }
                                        made = AiResult(bank, amt, dir, null, null, cmd, c.pattern)
                                        break
                                    }
                                }
                            } catch (_: Exception) {
                            }
                        }
                        res[i] = made
                        if (made == null) {
                            geminiIdx.add(i)
                            geminiNotifs.add(n)
                        }
                    }
                    if (geminiNotifs.isNotEmpty()) {
                        val gres = Gemini.extract(this, key, geminiNotifs, customLines)
                        gres.forEachIndexed { k, r ->
                            val i = geminiIdx[k]
                            if (r != null && r.command.isNotBlank()) res[i] = r
                        }
                    }
                    notifs.forEachIndexed { i, (bank, n, _) ->
                        if (res[i] != null) return@forEachIndexed
                        val full = "${n.title} ${n.text} ${n.bigText ?: ""}"
                        val tx = Parser.parseByBank(bank, full) ?: return@forEachIndexed
                        res[i] = AiResult(bank, tx.amount, tx.direction, tx.last4, tx.name, Parser.toCommand(tx), "")
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
                        Sink.send(this, r.command)
                        Config.markProcessed(this, keyNotif)
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
        etCustomCmd.setText(Learning.customRaw(this))
        cbBca.isChecked = true
        cbMandiri.isChecked = true
        cbBri.isChecked = true
    }

    private fun saveConfig() {
        val endpoint = etEndpoint.text?.toString()?.trim() ?: ""
        val chatId = etChatId.text?.toString()?.trim()?.toLongOrNull() ?: -1002031892399
        Config.save(this, endpoint, chatId, cbBca.isChecked, cbMandiri.isChecked, cbBri.isChecked)
        Learning.saveApiKey(this, etApiKey.text?.toString() ?: "")
        Learning.saveCustom(this, etCustomCmd.text?.toString() ?: "")
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
