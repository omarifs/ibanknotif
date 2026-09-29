package com.websolusi.ibanknotif

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Config {
    private const val PREF = "ibanknotif"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_CHAT_ID = "chat_id"
    private const val KEY_BCA = "bank_bca"
    private const val KEY_MANDIRI = "bank_mandiri"
    private const val KEY_BRI = "bank_bri"
    private const val KEY_LOG = "log"
    private const val KEY_PROCESSED_KEYS = "processed_keys"
    private const val KEY_PENDING_PAIRS = "pending_pairs"

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
    fun markProcessed(ctx: Context, var *keys: String) {
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

    // ---- Buffer [Pairer] ----

    /**
     * Sisi transfer yang sedang ditahan mencari pasangannya, disimpan ke disk.
     *
     * Buffernya in-memory di [Pairer], tapi Android bisa mati kapan saja dan
     * listener notifikasi sering dilepas di belakang layar oleh MIUI. Kalau sisi
     * yang tertahan hilang bersama proses, notifikasi bank itu hilang permanen -
     * lebih buruk dari bug yang [Pairer] perbaiki. Yang disimpan hanya string dan
     * angka, tidak ada objek Android, jadi aman ditulis sinkron di jalur notifikasi.
     */
    fun loadPending(ctx: Context): MutableList<Pairer.Pending> {
        val raw = prefs(ctx).getString(KEY_PENDING_PAIRS, null) ?: return mutableListOf()
        val out = mutableListOf<Pairer.Pending>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Pairer.Pending(
                        command = o.getString("command"),
                        accountId = o.getString("accountId"),
                        amount = o.getLong("amount"),
                        direction = Pairer.Direction.valueOf(o.getString("direction")),
                        atMs = o.getLong("atMs"),
                        dedupKey = o.optString("dedupKey").ifBlank { null }
                    )
                )
            }
        } catch (e: Exception) {
            // Buffer rusak tidak boleh menggagalkan seluruh jalur notifikasi.
            prefs(ctx).edit().remove(KEY_PENDING_PAIRS).commit()
            return mutableListOf()
        }
        return out
    }

    fun savePending(ctx: Context, pending: List<Pairer.Pending>) {
        val arr = JSONArray()
        pending.forEach { p ->
            arr.put(JSONObject().apply {
                put("command", p.command)
                put("accountId", p.accountId)
                put("amount", p.amount)
                put("direction", p.direction.name)
                put("atMs", p.atMs)
                put("dedupKey", p.dedupKey ?: "")
            })
        }
        // commit() sinkron: buffer baru ditulis sebelum proses bisa mati lagi.
        prefs(ctx).edit().putString(KEY_PENDING_PAIRS, arr.toString()).commit()
    }
}
