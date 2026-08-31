package com.websolusi.ibanknotif

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
    private lateinit var cbBca: MaterialCheckBox
    private lateinit var cbMandiri: MaterialCheckBox
    private lateinit var cbBri: MaterialCheckBox
    private lateinit var tvLog: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etEndpoint = findViewById(R.id.etEndpoint)
        etChatId = findViewById(R.id.etChatId)
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
    }

    override fun onResume() {
        super.onResume()
        tvLog.text = Config.getLog(this)
    }

    private fun loadConfig() {
        etEndpoint.setText(Config.endpoint(this))
        etChatId.setText(Config.chatId(this).toString())
        cbBca.isChecked = true
        cbMandiri.isChecked = true
        cbBri.isChecked = true
    }

    private fun saveConfig() {
        val endpoint = etEndpoint.text?.toString()?.trim() ?: ""
        val chatId = etChatId.text?.toString()?.trim()?.toLongOrNull() ?: -1002031892399
        Config.save(this, endpoint, chatId, cbBca.isChecked, cbMandiri.isChecked, cbBri.isChecked)
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
}
