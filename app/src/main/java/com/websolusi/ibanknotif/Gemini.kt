package com.websolusi.ibanknotif

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class AiResult(
    val bank: String,
    val amount: Long,
    val direction: String,   // "in", "out", "pindahin"
    val last4: String?,
    val ewallet: String?,
    val command: String,     // command final utk dikirim ke Telegram
    val pattern: String       // regex template utk auto-detect offline
) {
    fun render(): String {
        val b = StringBuilder()
        b.append("▪️ ").append(bank.uppercase())
        b.append(" · ").append(amount)
        b.append(" · ").append(direction)
        if (ewallet != null) b.append(" · ").append(ewallet)
        b.append("\n").append(command)
        if (pattern.isNotBlank()) b.append("\n").append("pola: ").append(pattern.take(90)).append("…")
        return b.toString()
    }
}

object Gemini {

    private const val MODEL = "gemini-2.0-flash"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    private val BANK_ALIAS = mapOf(
        "com.bca" to "bca", "com.bca.mybca" to "bca", "com.bca.mobile" to "bca", "com.bca.mbs" to "bca",
        "co.id.mandiri.livin" to "mandiri", "co.id.mandiri.touch" to "mandiri",
        "id.co.bri.brimo" to "bri", "com.bri.brimo" to "bri", "id.co.bri.www.brimo" to "bri"
    )

    data class RawNotif(val pkg: String, val title: String, val text: String, val bigText: String?)

    fun bankLabel(pkg: String): String? = BANK_ALIAS[pkg]

    /**
     * Kirim daftar notif + kamus bank + aturan format ke Gemini.
     * Return list AiResult sejajar dgn `notifs` (null utk yg gagal/tak transaksi).
     */
    fun extract(ctx: Context, apiKey: String, notifs: List<RawNotif>): List<AiResult?> {
        val res = ArrayList<AiResult?>(notifs.size)
        repeat(notifs.size) { res.add(null) }

        val prompt = buildPrompt(notifs)
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply { put("parts", JSONArray().apply { put(JSONObject().apply { put("text", prompt) }) }) })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 4096)
            })
        }

        val url = URL("$BASE?key=$apiKey")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }

            val code = conn.responseCode
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                Config.appendLog(ctx, "GEMINI ERR $code: ${raw.take(300)}")
                return res
            }
            val json = JSONObject(raw)
            val textOut = json.getJSONArray("candidates")
                .getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0)
                .getString("text")
            parseResults(ctx, textOut, res)
        } catch (e: Exception) {
            Config.appendLog(ctx, "GEMINI EXC: ${e.message}")
        } finally {
            conn.disconnect()
        }
        return res
    }

    private fun buildPrompt(notifs: List<RawNotif>): String {
        val kamus = """
            Bank recognition:
            - 'bri' -> id 'bri.ibu' utk rek 8533, 'bri.vidha' utk rek lain. Default utk topup e-wallet (tanpa rekening): 'bri.ibu'.
            - 'bca' -> id 'bca'
            - 'mandiri' -> id 'mandiri'
            Rekening BRI: 8533 = ibu, lainnya = vidha.
            Aturan command untuk Telegram:
            - dana MASUK (credited, diterima, masuk, received) -> /tabin <id> <amount>
            - dana KELUAR (debit, spent, terbayar, transfer keluar, top up e-wallet) -> /tabout <id> <amount>
            - transfer ANTAR REKENING SENDIRI (transfer dari rek kita ke rek kita sendiri) -> /pindahin <id> tujuan.rek <amount>
            - <amount> = angka bulat rupiah TANPA titik/koma (contoh: 200000, bukan 200.000, bukan Rp200.000).
            Bila teks tidak mengandung transaksi (promo, info, iklan, saldo biasa tanpa nominal transaksi), output null.
        """.trimIndent()

        val sb = StringBuilder()
        sb.append("Kamu ekstraktor transaksi bank. Kamus & aturan:\n").append(kamus).append("\n\n")
        sb.append("Berikut daftar notifikasi. Untuk SETIAP notif, tentukan apakah transaksi dan beri:\n")
        sb.append("{\"index\":<n>,\"bank\":\"bri|bca|mandiri\",\"amount\":<long>,\"direction\":\"in|out|pindahin\",\"last4\":\"xxxx\" atau null,\"ewallet\":\"nama\" atau null,\"command\":\"/tab...\",\"pattern\":\"<regex kotlin yg cocok dgn seluruh teks notif ini, flag IGNORE_CASE>\"}\n")
        sb.append("Keluarkan JSON ARRAY hanya (tanpa markdown, tanpa teks lain). Untuk yang bukan transaksi gunakan {\"index\":<n>,\"command\":null}.\n\n")
        notifs.forEachIndexed { i, n ->
            sb.append("[$i] pkg=${n.pkg} title=${n.title}\ntext=${n.text}\nbig=${n.bigText ?: ""}\n\n")
        }
        return sb.toString()
    }

    private fun parseResults(ctx: Context, rawText: String, res: ArrayList<AiResult?>) {
        val cleaned = rawText.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = JSONArray(cleaned)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val idx = o.optInt("index", -1)
            if (idx < 0 || idx >= res.size) continue
            if (o.isNull("command") || o.optString("command").isBlank()) continue
            val bank = o.optString("bank", "")
            val last4 = if (o.isNull("last4")) null else o.optString("last4")
            val ewallet = if (o.isNull("ewallet")) null else o.optString("ewallet")
            res[idx] = AiResult(
                bank = bank,
                amount = o.optLong("amount", 0),
                direction = o.optString("direction", "out"),
                last4 = last4,
                ewallet = ewallet,
                command = o.optString("command"),
                pattern = o.optString("pattern", "")
            )
        }
    }
}
