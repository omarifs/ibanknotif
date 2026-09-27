package com.websolusi.ibanknotif

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Penyimpanan [RuleSet].
 *
 * Sumber, berurutan dari yang paling dipercaya: override lokal di
 * SharedPreferences, hasil tarikan dari server, lalu `assets/rules.json` bawaan.
 * Dengan begitu aplikasi tetap berfungsi penuh tanpa internet dan tanpa server.
 */
object RuleStore {

    private const val PREF = "ibanknotif"
    private const val KEY_OVERRIDE = "rules_json"
    private const val KEY_HISTORY = "rules_history"   // "versi|json" dipisah baris baru
    private const val KEY_SYNCED_VERSION = "rules_synced_version"
    private const val KEY_USER_EDITED = "rules_user_edited"
    private const val HISTORY_LIMIT = 3

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * Aturan yang sedang berlaku. Sengaja TANPA jaringan: fungsi ini dipanggil
     * dari dalam listener notifikasi, dan satu panggilan HTTP di sana akan
     * menahan pemrosesan notif. Tarikan dari server hanya lewat
     * [fetchFromServer], dipanggil manual dari layar Aturan.
     */
    fun load(ctx: Context): RuleSet {
        prefs(ctx).getString(KEY_OVERRIDE, null)?.let { raw ->
            RuleSet.fromJson(raw)?.let { return it }
        }
        return bundled(ctx)
    }

    /** Aturan bawaan yang ditanam di APK. Selalu ada, selalu bisa dipulihkan. */
    fun bundled(ctx: Context): RuleSet = try {
        RuleSet.fromJson(ctx.assets.open("rules.json").bufferedReader().use(BufferedReader::readText))
            ?: RuleSet(0, RuleSet.defaultAccounts(), emptyList())
    } catch (e: Exception) {
        RuleSet(0, RuleSet.defaultAccounts(), emptyList())
    }

    /** Ada salinan aturan lokal, baik dari server maupun hasil edit manual. */
    fun isOverridden(ctx: Context): Boolean = prefs(ctx).contains(KEY_OVERRIDE)

    /**
     * Aturan lokal ini disentuh manusia, bukan sekadar hasil tarikan server.
     * Hanya yang ini yang reluctant ditimpa tarikan berikutnya.
     */
    fun isUserEdited(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_USER_EDITED, false)

    /**
     * Simpan aturan baru. Ditolak bila tidak lolos [RuleValidator], dan versi
     * sebelumnya otomatis masuk riwayat.
     */
    fun save(ctx: Context, set: RuleSet): String? {
        RuleValidator.validateAll(set)?.let { return it }
        val current = load(ctx)
        if (isOverridden(ctx)) {
            pushHistory(ctx, current)
        }
        prefs(ctx).edit()
            .putString(KEY_OVERRIDE, RuleSet.toJson(set.copy(version = current.version + 1)))
            .putBoolean(KEY_USER_EDITED, true)
            .apply()
        return null
    }

    /** Kembalikan ke aturan bawaan APK. */
    fun reset(ctx: Context) {
        pushHistory(ctx, load(ctx))
        prefs(ctx).edit().remove(KEY_OVERRIDE).remove(KEY_USER_EDITED).apply()
    }

    /** Riwayat versi tersimpan, terbaru lebih dulu. */
    fun history(ctx: Context): List<RuleSet> {
        val raw = prefs(ctx).getString(KEY_HISTORY, "") ?: return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val json = line.substringAfter('\u0001', "")
            if (json.isBlank()) null else RuleSet.fromJson(json)
        }
    }

    private fun pushHistory(ctx: Context, set: RuleSet) {
        val current = history(ctx).map { RuleSet.toJson(it) }.toMutableList()
        current.add(0, "${set.version}\u0001${RuleSet.toJson(set)}")
        prefs(ctx).edit()
            .putString(KEY_HISTORY, current.take(HISTORY_LIMIT).joinToString("\n"))
            .apply()
    }

    /** Kembalikan ke salah satu versi riwayat. */
    fun rollback(ctx: Context, target: RuleSet): String? = save(ctx, target)

    // ---- sinkron dari server (hanya menarik, tidak menulis) ----

    fun rulesUrl(ctx: Context): String {
        val ep = Config.endpoint(ctx)
        val i = ep.lastIndexOf('/')
        return (if (i > 0) ep.substring(0, i + 1) else ep) + "rules.json"
    }

    /**
     * Tarik `rules.json` dari server. Hanya dipakai kalau versinya lebih baru
     * dari yang tersimpan. Mengembalikan pesan hasil, atau null kalau tak ada
     * yang berubah.
     */
    fun fetchFromServer(ctx: Context): String? {
        val url = rulesUrl(ctx)
        val body = try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode !in 200..299) return null
            c.inputStream.bufferedReader().use(BufferedReader::readText)
        } catch (e: Exception) {
            Config.appendLog(ctx, "SYNC aturan gagal: ${e.message}")
            return null
        }
        val remote = RuleSet.fromJson(body) ?: return null
        RuleValidator.validateAll(remote)?.let {
            Config.appendLog(ctx, "SYNC aturan ditolak: $it")
            return null
        }
        val last = syncedVersion(ctx)
        if (remote.version == last) return "Aturan di server sudah versi ${remote.version}, sama dengan yang dipakai."
        if (isUserEdited(ctx)) {
            return "Di HP ini ada aturan hasil edit manual (versi ${load(ctx).version}). " +
                "Aturan server versi ${remote.version} TIDAK ditimpa supaya hasil kerjamu hilang. " +
                "Tekan Bawaan dulu kalau mau kembali ke versi server."
        }
        prefs(ctx).edit()
            .putString(KEY_OVERRIDE, body)
            .putInt(KEY_SYNCED_VERSION, remote.version)
            .apply()
        Config.appendLog(ctx, "SYNC aturan: versi server ${remote.version} dipakai")
        return "Aturan diperbarui dari server, versi ${remote.version}."
    }

    fun syncedVersion(ctx: Context): Int = prefs(ctx).getInt(KEY_SYNCED_VERSION, 0)

    fun describe(ctx: Context): String {
        val set = load(ctx)
        val asal = when {
            isOverridden(ctx) -> "diperbaiki manual"
            syncedVersion(ctx) > 0 -> "dari server"
            else -> "bawaan aplikasi"
        }
        val sb = StringBuilder()
        sb.append("versi ").append(set.version).append(" (").append(asal).append(")\n")
        sb.append("rekening: ")
        sb.append(set.entries.joinToString(", ") { "${it.key} = ${it.value}" })
        sb.append("\n\n")
        val shown = LinkedHashSet<String>()
        for (r in set.rules.sortedByDescending { it.priority }) {
            if (!shown.add(r.id)) continue
            sb.append("[").append(r.priority).append("] ")
            sb.append(if (r.enabled) "" else "(mati) ")
            sb.append(r.name).append("\n")
            sb.append("     bank=").append(r.bank)
            sb.append("  when=").append(r.when.joinToString(" & "))
            sb.append("\n     -> ").append(if (r.stop) "diam" else r.command).append("\n\n")
        }
        return sb.toString()
    }

    fun exportJson(ctx: Context): String = JSONObject().apply {
        put("rules", RuleSet.toJson(load(ctx)))
    }.toString(2)
}
