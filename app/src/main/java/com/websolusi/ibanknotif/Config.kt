package com.websolusi.ibanknotif

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Config {
    private const val PREF = "ibanknotif"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_CHAT_ID = "chat_id"
    private const val KEY_DEVICE_ACCOUNT = "device_account"
    private const val KEY_BCA = "bank_bca"
    private const val KEY_MANDIRI = "bank_mandiri"
    private const val KEY_BRI = "bank_bri"
    private const val KEY_LOG = "log"
    private const val KEY_PROCESSED_KEYS = "processed_keys"
    private const val KEY_PENDING_PAIRS = "pending_pairs"
    private const val KEY_OUTBOX = "outbox"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun endpoint(ctx: Context): String =
        prefs(ctx).getString(KEY_ENDPOINT, "https://tools.websolusi.com/telegram/telegram.php") ?: ""

    fun chatId(ctx: Context): Long =
        prefs(ctx).getLong(KEY_CHAT_ID, -1002031892399)

    fun deviceAccount(ctx: Context): String? =
        prefs(ctx).getString(KEY_DEVICE_ACCOUNT, null)?.trim()?.ifBlank { null }

    fun setDeviceAccount(ctx: Context, id: String) {
        val v = id.trim()
        if (v.isBlank()) prefs(ctx).edit().remove(KEY_DEVICE_ACCOUNT).apply()
        else prefs(ctx).edit().putString(KEY_DEVICE_ACCOUNT, v).apply()
    }

    fun bankLabel(pkg: String): String? = PatternStore.bankLabel(pkg)

    fun isBankEnabled(ctx: Context, pkg: String): Boolean =
        bankLabel(pkg)?.let { isBankEnabledByLabel(ctx, it) } ?: false

    fun isBankEnabledByLabel(ctx: Context, label: String): Boolean {
        val p = prefs(ctx)
        return when (label) {
            "bca" -> p.getBoolean(KEY_BCA, true)
            "mandiri" -> p.getBoolean(KEY_MANDIRI, true)
            "bri" -> p.getBoolean(KEY_BRI, true)
            // Bank yang belum punya tombol di UI: aktifkan. Menolaknya berarti
            // bank yang baru ditambah lewat patterns.json tidak akan pernah jalan.
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
        val max = runtimeOf(ctx).logLines
        val trimmed = newLog.lines().take(max).joinToString("\n")
        p.edit().putString(KEY_LOG, trimmed).apply()
    }

    /** Setelan housekeeping aktif. Tidak pernah menyentuh jaringan. */
    private fun runtimeOf(ctx: Context): RuntimeConfig = try {
        RuleStore.load(ctx).runtime
    } catch (_: Exception) {
        RuntimeConfig()
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
            // Jaga ukuran agar SharedPreferences tidak membengkak.
            val max = runtimeOf(ctx).maxProcessedKeys
            val trimmed = if (current.size > max) current.toList().takeLast(max).toSet() else current
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

    // ---- Outbox: command yang sudah diputuskan tapi belum tentu terkirim ----

    /**
     * Satu command yang menunggu terkirim.
     *
     * Kenapa ini ada: [Sink] punya antrean in-memory, dan MIUI sering membunuh
     * proses dari belakang layar. Kalau notifikasi ditandai "sudah diproses"
     * padahal request-nya belum sampai, notifikasi itu tidak akan pernah dikirim
     * lagi - `isProcessed` menyingkirkannya di setiap catch-up berikutnya, dan
     * `dedup_key`-nya sudah diklaim di server, jadi kirim ulang juga ditolak.
     * Itu yang membuat satu transfer Gopay hilang tanpa jejak.
     *
     * Jadi urutannya dibalik: command ditulis ke sini **dulu**, baru ditandai
     * diproses, baru dikirim. Yang tersisa di sini dikirim ulang saat aplikasi
     * dibuka. Kuncinya ikut disimpan supaya kirim ulang tetap aman.
     */
    data class Outbox(val command: String, val dedupKey: String?, val atMs: Long)

    fun loadOutbox(ctx: Context): MutableList<Outbox> {
        val raw = prefs(ctx).getString(KEY_OUTBOX, null) ?: return mutableListOf()
        val out = mutableListOf<Outbox>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Outbox(
                        command = o.getString("command"),
                        dedupKey = o.optString("dedupKey").ifBlank { null },
                        atMs = o.getLong("atMs")
                    )
                )
            }
        } catch (_: Exception) {
            // Outbox rusak tidak boleh menggagalkan jalur notifikasi. Data di
            // dalamnya sudah punya dedup_key, jadi masih bisa dikirim ulang.
            prefs(ctx).edit().remove(KEY_OUTBOX).commit()
            return mutableListOf()
        }
        return out
    }

    fun saveOutbox(ctx: Context, items: List<Outbox>) {
        val arr = JSONArray()
        items.forEach { p ->
            arr.put(JSONObject().apply {
                put("command", p.command)
                put("dedupKey", p.dedupKey ?: "")
                put("atMs", p.atMs)
            })
        }
        prefs(ctx).edit().putString(KEY_OUTBOX, arr.toString()).commit()
    }

    /** Tambahkan satu command ke outbox, menyingkirkan duplikat yang identik. */
    fun enqueueOutbox(ctx: Context, item: Outbox) {
        val items = loadOutbox(ctx)
        if (items.any { it.command == item.command && it.dedupKey == item.dedupKey }) return
        items.add(item)
        saveOutbox(ctx, items)
    }

    /** Buang item yang sudah dianggap terkirim. */
    fun ackOutbox(ctx: Context, item: Outbox) {
        val items = loadOutbox(ctx)
        val sisa = items.filterNot { it.command == item.command && it.dedupKey == item.dedupKey }
        if (sisa.size != items.size) saveOutbox(ctx, sisa)
    }
}
