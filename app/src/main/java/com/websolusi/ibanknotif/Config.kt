package com.websolusi.ibanknotif

import android.content.Context
import android.content.SharedPreferences

object Config {
    private const val PREF = "ibanknotif"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_CHAT_ID = "chat_id"
    private const val KEY_BCA = "bank_bca"
    private const val KEY_MANDIRI = "bank_mandiri"
    private const val KEY_BRI = "bank_bri"
    private const val KEY_LOG = "log"
    private const val KEY_PROCESSED_KEYS = "processed_keys"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun endpoint(ctx: Context): String =
        prefs(ctx).getString(KEY_ENDPOINT, "https://tools.websolusi.com/telegram/telegram.php") ?: ""

    fun chatId(ctx: Context): Long =
        prefs(ctx).getLong(KEY_CHAT_ID, -1002031892399)

    fun bankLabel(pkg: String): String? = PatternStore.bankLabel(pkg)

    fun isBankEnabled(ctx: Context, pkg: String): Boolean {
        val p = prefs(ctx)
        val label = bankLabel(pkg) ?: return false
        return when (label) {
            "bca" -> p.getBoolean(KEY_BCA, true)
            "mandiri" -> p.getBoolean(KEY_MANDIRI, true)
            "bri" -> p.getBoolean(KEY_BRI, true)
            // Bank yang belum punya tombol di UI: aktifkan. Menolaknya berarti
            // bank yang baru ditambah lewat patterns.json tidak akan pernah jalan.
            else -> true
        }
    }

    fun isBankEnabledByLabel(label: String): Boolean {
        return when (label) {
            "bca" -> true
            "mandiri" -> true
            "bri" -> true
            else -> true
        }
    }

    fun save(ctx: Context, endpoint: String, chatId: Long, bca: Boolean, mandiri: Boolean, bri: Boolean) {
        prefs(ctx).edit()
            .putString(KEY_ENDPOINT, endpoint)
            .putLong(KEY_CHAT_ID, chatId)
            .putBoolean(KEY_BCA, bca)
            .putBoolean(KEY_MANDIRI, mandiri)
            .putBoolean(KEY_BRI, bri)
            .apply()
    }

    fun appendLog(ctx: Context, line: String) {
        val p = prefs(ctx)
        val old = p.getString(KEY_LOG, "") ?: ""
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val newLog = "$ts $line\n$old"
        val trimmed = newLog.lines().take(100).joinToString("\n")
        p.edit().putString(KEY_LOG, trimmed).apply()
    }

    fun getLog(ctx: Context): String =
        prefs(ctx).getString(KEY_LOG, "") ?: ""

    fun clearLog(ctx: Context) {
        prefs(ctx).edit().remove(KEY_LOG).apply()
    }

    fun isProcessed(ctx: Context, key: String): Boolean {
        val p = prefs(ctx)
        synchronized(p) {
            val set = p.getStringSet(KEY_PROCESSED_KEYS, emptySet()) ?: emptySet()
            return set.contains(key)
        }
    }

    /**
     * Tandai notif sebagai sudah diproses.
     * Wajib commit() sinkron + satu kunci untuk beberapa key sekaligus: kalau
     * apply() dipakai, panggilan berikutnya bisa membaca daftar lama dan
     * menimpa key yang baru saja ditulis -> guard anti dobel jadi bocor.
     */
    fun markProcessed(ctx: Context, vararg keys: String) {
        if (keys.isEmpty()) return
        val p = prefs(ctx)
        synchronized(p) {
            val current = (p.getStringSet(KEY_PROCESSED_KEYS, emptySet()) ?: emptySet()).toMutableSet()
            current.addAll(keys)
            // jaga ukuran max 200 key agar SharedPreferences tak membengkak
            val trimmed = if (current.size > 200) current.toList().takeLast(200).toSet() else current
            p.edit().putStringSet(KEY_PROCESSED_KEYS, trimmed).commit()
        }
    }
}
