package com.websolusi.ibanknotif

import android.content.ComponentName
import android.content.Intent
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
    private lateinit var cbBca: MaterialCheckBox
    private lateinit var cbMandiri: MaterialCheckBox
    private lateinit var cbBri: MaterialCheckBox
    private lateinit var tvLog: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etEndpoint = findViewById(R.id.etEndpoint)
        etChatId = findViewById(R.id.etChatId)
        etApiKey = findViewById(R.id.etApiKey)
        cbBca = findViewById(R.id.cbBca)
        cbMandiri = findViewById(R.id.cbMandiri)
        cbBri = findViewById(R.id.cbBri)
        tvLog = findViewById(R.id.tvLog)

        loadConfig()

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
                val notifs = active.mapNotNull { sbn ->
                val pkg = sbn.packageName
                val bank = Gemini.bankLabel(pkg) ?: return@mapNotNull null
                val ex = sbn.notification?.extras ?: return@mapNotNull null
                val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
                val text = ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
                val big = ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
                Gemini.RawNotif(pkg, title, text, big)
            }
                if (notifs.isEmpty()) {
                    Toast.makeText(this, "Tidak ada notifikasi bank yang cocok", Toast.LENGTH_LONG).show()
                    return@withService
                }
                Toast.makeText(this, "Menganalisis ${notifs.size} notif dengan AI...", Toast.LENGTH_SHORT).show()
            Thread {
                val results = Gemini.extract(this, key, notifs)
                val valid = results.filterNotNull().filter { it.command.isNotBlank() }
                if (valid.isEmpty()) {
                    runOnUiThread {
                        Config.appendLog(this, "AI: tidak ada transaksi terdeteksi")
                        tvLog.text = Config.getLog(this)
                        Toast.makeText(this, "AI tidak menemukan transaksi", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                runOnUiThread {
                    Config.appendLog(this, "AI DETEKSI ${valid.size} transaksi")
                    val summary = valid.joinToString("\n\n") { it.render() }
                    tvLog.text = Config.getLog(this)
                    AlertDialog.Builder(this)
                        .setTitle("Hasil Analisis AI")
                        .setMessage(summary)
                        .setPositiveButton("Simpan Belajar") { _, _ ->
                            Learning.addTemplates(this, valid)
                            Config.appendLog(this, "Belajar tersimpan: ${valid.size} template")
                            tvLog.text = Config.getLog(this)
                            Toast.makeText(this, "Template belajar tersimpan", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Batal", null)
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
    }

    private fun loadConfig() {
        etEndpoint.setText(Config.endpoint(this))
        etChatId.setText(Config.chatId(this).toString())
        etApiKey.setText(Learning.apiKey(this))
        cbBca.isChecked = true
        cbMandiri.isChecked = true
        cbBri.isChecked = true
    }

    private fun saveConfig() {
        val endpoint = etEndpoint.text?.toString()?.trim() ?: ""
        val chatId = etChatId.text?.toString()?.trim()?.toLongOrNull() ?: -1002031892399
        Config.save(this, endpoint, chatId, cbBca.isChecked, cbMandiri.isChecked, cbBri.isChecked)
        Learning.saveApiKey(this, etApiKey.text?.toString() ?: "")
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
        ensureServiceRunning()?.let(action) ?: run {
            val delays = longArrayOf(1000, 2000, 4000)
            fun retry(index: Int) {
                Handler(Looper.getMainLooper()).postDelayed({
                    val service = NotifService.instance
                    if (service != null) action(service)
                    else if (index < delays.lastIndex) retry(index + 1)
                    else Toast.makeText(this, "Service belum siap. Periksa akses notifikasi dan baterai.", Toast.LENGTH_LONG).show()
                }, delays[index])
            }
            if (isNotifServiceEnabled()) retry(0)
        }
    }

    private fun ensureServiceRunning(): NotifService? {
        if (!isNotifServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Akses Notifikasi Mati")
                .setMessage("Izin akses notifikasi belum aktif. Aktifkan IBankNotif di pengaturan izin notifikasi, lalu kembali ke sini.")
                .setPositiveButton("Buka Pengaturan") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Batal", null)
                .show()
            return null
        }
        val service = NotifService.instance
        if (service != null) return service
        NotificationListenerService.requestRebind(
            ComponentName(this, NotifService::class.java)
        )
        Toast.makeText(this, "Service sedang diaktifkan...", Toast.LENGTH_SHORT).show()
        Config.appendLog(this, "SERVICE REBIND diminta (izin OK, instance null)")
        return null
    }
}
