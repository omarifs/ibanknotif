package com.websolusi.ibanknotif

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Simpan & baca "hasil belajar" dari pindai AI.
 * Setiap template = pola regex utk mencocokkan notif + command final utk Telegram.
 * Disimpan lokal di SharedPreferences (offline, tanpa panggil AI ulang saat runtime).
 */
object Learning {

    private const val KEY_API = "gemini_api_key"
    private const val KEY_TEMPLATES = "learned_templates"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("ibanknotif", Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String = prefs(ctx).getString(KEY_API, "") ?: ""
    fun saveApiKey(ctx: Context, key: String) {
        prefs(ctx).edit().putString(KEY_API, key.trim()).apply()
    }

    data class Template(
        val bank: String,
        val pattern: String,
        val command: String
    )

    fun templates(ctx: Context): List<Template> {
        val raw = prefs(ctx).getString(KEY_TEMPLATES, "") ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Template(
                    bank = o.optString("bank", ""),
                    pattern = o.optString("pattern", ""),
                    command = o.optString("command", "")
                )
            }.filter { it.pattern.isNotBlank() && it.command.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addTemplates(ctx: Context, results: List<AiResult>) {
        val valid = results.filter { it.pattern.isNotBlank() && it.command.isNotBlank() }
        if (valid.isEmpty()) return
        val arr = JSONArray()
        val existing = templates(ctx)
        existing.forEach { arr.put(JSONObject().apply {
            put("bank", it.bank); put("pattern", it.pattern); put("command", it.command)
        }) }
        valid.forEach { arr.put(JSONObject().apply {
            put("bank", it.bank); put("pattern", it.pattern); put("command", it.command)
        }) }
        prefs(ctx).edit().putString(KEY_TEMPLATES, arr.toString()).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_TEMPLATES).apply()
    }

    /** Cari template pertama yang cocok dgn teks notif utk bank tertentu. Return command final atau null. */
    fun match(bank: String, text: String, ctx: Context): String? {
        for (t in templates(ctx)) {
            if (t.bank.isNotBlank() && t.bank != bank) continue
            try {
                val re = Regex(t.pattern, RegexOption.IGNORE_CASE)
                if (re.containsMatchIn(text)) return t.command
            } catch (e: Exception) {
                // pola invalid -> lewati
            }
        }
        return null
    }
}
