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

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun endpoint(ctx: Context): String =
        prefs(ctx).getString(KEY_ENDPOINT, "https://tools.websolusi.com/telegram/telegram.php") ?: ""

    fun chatId(ctx: Context): Long =
        prefs(ctx).getLong(KEY_CHAT_ID, -1002031892399)

    fun isBankEnabled(ctx: Context, pkg: String): Boolean {
        val p = prefs(ctx)
        return when (pkg) {
            "com.bca" -> p.getBoolean(KEY_BCA, true)
            "co.id.mandiri.livin" -> p.getBoolean(KEY_MANDIRI, true)
            "id.co.bri.brimo" -> p.getBoolean(KEY_BRI, true)
            else -> false
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
}
