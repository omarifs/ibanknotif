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
    fun extract(ctx: Context, apiKey: String, notifs: List<RawNotif>, customPrompt: String? = null): List<AiResult?> {
        val res = ArrayList<AiResult?>(notifs.size)
        repeat(notifs.size) { res.add(null) }

        val prompt = buildPrompt(notifs, customPrompt)
        val raw = call(ctx, apiKey, prompt) ?: return res
        parseResults(ctx, raw, res)
        return res
    }

    /** Panggilan HTTP tunggal ke Gemini. Null kalau gagal; errornya sudah masuk log. */
    private fun call(ctx: Context, apiKey: String, prompt: String): String? {
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply { put("parts", JSONArray().apply { put(JSONObject().apply { put("text", prompt) }) }) })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 4096)
            })
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("$BASE?key=$apiKey").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Config.appendLog(ctx, "GEMINI ERR $code: ${err.take(300)}")
                return null
            }
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                .getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0).getString("text")
        } catch (e: Exception) {
            // Jangan pernah tulis API key ke log: pesan exception bisa memuat URL lengkap.
            Config.appendLog(ctx, "GEMINI EXC: ${e.message?.replace(Regex("key=[^&\\s]+"), "key=***")}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun buildPrompt(notifs: List<RawNotif>, customPrompt: String?): String {
        val kamus = """
            Rekening milik sendiri yang sah HANYA dua:
            - 8533 = bri.ibu  -> id "bri.ibu"
            - 3508 = bri.vidha -> id "bri.vidha"
            Semua nomor rekening lain = pihak luar.

            Menentukan arah WAJIB dari rekening asal ("dari ...") dan tujuan
            ("nomor rekening tujuan ..." / "ke rekening ..."), BUKAN dari kata
            "pindah dana", "pindah saldo", "ke rekening sendiri", atau "dana".

            - ASAL dan TUJUAN keduanya 8533/3508, dan berbeda -> /pindahin <id_asal> <id_tujuan> <amount>
            - ASAL 8533/3508, tujuan pihak luar -> /tabout <id_asal> <amount>
            - TUJUAN 8533/3508, asal pihak luar -> /tabin <id_tujuan> <amount>
            - Tidak ada nomor rekening sama sekali -> pakai kata:
                masuk / kredit / diterima / dana masuk / received / credited -> /tabin
                keluar / debit / bayar / transfer / top up / pembelian / pembayaran -> /tabout
            - myBCA: "You spent IDR x" -> /tabout bca x ; "You received IDR x" -> /tabin bca x

            Dilarang memakai kata "Account Transfer" milik myBCA sebagai bukti transfer
            antar rekening sendiri: itu kategori transaksi biasa, id-nya "bca".

            <amount> = angka bulat rupiah TANPA titik/koma (contoh: 200000).

            JANGAN PERNAH menulis id tanpa nama rekening, misalnya "/tabout bri 200000".
            Itu akan membuat akun baru. Untuk BRI tanpa nomor rekening di notif, pakai "bri.ibu".

            KAPAN BUKAN TRANSAKSI (output null) HANYA jika: tidak ada nominal rupiah sama sekali,
            atau teks jelas promo/iklan/info/OIN/maintenance/saldo tampilan. Promo produk seperti
            "SR025" dan pengumuman maintenance bank bukan transaksi.

            Contoh benar:
            1. "Top Up ShopeePay 083124253640 sebesar Rp200.000,00" -> /tabout bri.ibu 200000
            2. "Transfer dari XXXXXXXXXX8533 dengan nomor rekening tujuan XXXXXXXXXX3508 sebesar Rp100.000,00" -> /pindahin bri.ibu bri.vidha 100000
            3. "Transfer dari XXXXXXXXXX3508 dengan nomor rekening tujuan XXXXXXXXXX8533 sebesar Rp100.000,00" -> /pindahin bri.vidha bri.ibu 100000
            4. "Transfer dari XXXXXXXXXX5530 dengan nomor rekening tujuan XXXXXXXXXX8533 sebesar Rp10.000,00" -> /tabin bri.ibu 10000
               (5530 bukan rekening sendiri, jadi ini uang masuk, bukan pindahin)
            5. "Sobat BRI! Dana Rp10.000 masuk ke rekening 000000000008533" -> /tabin bri.ibu 10000
            6. myBCA "Kredit Rp1.500.000 dari ..." -> /tabin bca 1500000
            7. Livin "Debit Rp52.000 untuk QRIS ..." -> /tabout mandiri 52000

            pattern = <regex kotlin IGNORE_CASE yg mencocokkan bagian inti teks notif ini>.
            Untuk notif transfer, pattern WAJIB mengikat kata "dari" dan kata tujuan,
            bukan hanya nomor rekening.
            Hindari lookbehind/references complex; regex sederhana.
        """.trimIndent()

        val sb = StringBuilder()
        sb.append("Kamu ekstraktor transaksi bank. Kamus & aturan:\n").append(kamus).append("\n\n")
        if (!customPrompt.isNullOrBlank()) {
            sb.append("INSTRUKSI TAMBAHAN DARI PENGGUNA (prioritas tertinggi, menimpa aturan di atas bila bertentangan):\n")
            sb.append(customPrompt.trim()).append("\n\n")
        }
        sb.append("Berikut daftar notifikasi. Untuk SETIAP notif, tentukan apakah transaksi dan beri:\n")
        sb.append("{\"index\":<n>,\"bank\":\"bri|bca|mandiri\",\"amount\":<long>,\"direction\":\"in|out|pindahin\",\"last4\":\"xxxx\" atau null,\"ewallet\":\"nama\" atau null,\"command\":\"/tab...\",\"pattern\":\"<regex kotlin yg cocok dgn seluruh teks notif ini, flag IGNORE_CASE>\"}\n")
        sb.append("Keluarkan JSON ARRAY hanya (tanpa markdown, tanpa teks lain). Untuk yang bukan transaksi gunakan {\"index\":<n>,\"command\":null}.\n\n")
        notifs.forEachIndexed { i, n ->
            sb.append("[$i] pkg=${n.pkg} title=${n.title}\ntext=${n.text}\nbig=${n.bigText ?: ""}\n\n")
        }
        return sb.toString()
    }

    data class RuleSuggestion(
        val rule: Rule?,
        val rawJson: String,
        val error: String?
    )

    /**
     * Minta Gemini proposes SATU aturan baru, bukan sekadar command.
     *
     * Aturan yangRules kasar sudah dipakai di app: [ruleJson]. Kandidat dari
     * notifikasi asli ada di [samples]. Hasilnya tetap divalidasi ulang di
     * sisi app oleh [RuleValidator] sebelum bisa disimpan, jadi jawaban AI
     * tidak pernah dipercaya begitu saja.
     */
    fun suggestRule(
        ctx: Context,
        apiKey: String,
        samples: List<RawNotif>,
        currentRules: RuleSet,
        currentOutcome: String,
        customPrompt: String?
    ): RuleSuggestion {
        if (apiKey.isBlank()) return RuleSuggestion(null, "", "Gemini API Key belum diisi")
        if (samples.isEmpty()) return RuleSuggestion(null, "", "Tidak ada notifikasi untuk dianalisis")

        val prompt = buildRulePrompt(samples, currentRules, currentOutcome, customPrompt)
        val raw = call(ctx, apiKey, prompt) ?: return RuleSuggestion(null, "", "Panggilan Gemini gagal")
        return parseRuleSuggestion(raw, currentRules)
    }

    fun parseRuleSuggestion(raw: String, current: RuleSet): RuleSuggestion {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val candidates = try {
            val trimmed = if (cleaned.startsWith("[")) cleaned else "[$cleaned]"
            val arr = JSONArray(trimmed)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (e: Exception) {
            return RuleSuggestion(null, raw, "Jawaban AI bukan JSON yang bisa dibaca: ${e.message}")
        }
        if (candidates.isEmpty()) return RuleSuggestion(null, raw, "AI tidak mengusulkan aturan")

        val problems = ArrayList<String>()
        for (o in candidates) {
            val w = o.optJSONArray("when") ?: JSONArray()
            val rule = Rule(
                id = o.optString("id", ""),
                name = o.optString("name", o.optString("id", "")),
                bank = o.optString("bank", "*"),
                priority = o.optInt("priority", 0),
                enabled = o.optBoolean("enabled", true),
                when = (0 until w.length()).map { w.getString(it) },
                command = o.optString("command", ""),
                stop = o.optBoolean("stop", false),
                defaultId = o.optString("defaultId").ifBlank { null }
            )
            val err = RuleValidator.validate(rule, current)
            if (err == null) return RuleSuggestion(rule, raw, null)
            problems.add("${rule.id.ifBlank { "(tanpa id)" }}: $err")
        }
        return RuleSuggestion(null, raw, "Semua usulan AI ditolak:\n" + problems.joinToString("\n") { "  - $it" })
    }

    private fun buildRulePrompt(
        samples: List<RawNotif>,
        current: RuleSet,
        currentOutcome: String,
        customPrompt: String?
    ): String {
        val sb = StringBuilder()
        sb.append("Kamu diminta proposes SATU aturan klasifikasi baru untuk aplikasi\
")
            .append("pembaca notifikasi bank. Balas HANYA JSON array, tanpa teks lain.\n\n")
        sb.append("KOSAKATA 'when' YANG BOLEH (tidak ada yang lain, tanpa operator bebas):\n")
        sb.append("  ").append(RuleSet.PREDICATES.joinToString(", ")).append("\n")
        sb.append("  has:kata    not:kata    has_any:kata1|kata2    has_none:kata1|kata2\n")
        sb.append("  amount_gt:N   amount_lt:N\n\n")
        sb.append("id akun yang boleh muncul di command: ")
            .append(RuleSet.KNOWN_IDS.joinToString(", ")).append("\n\n")
        sb.append("ATURAN WAJIB:\n")
        sb.append("1. 'when' WAJIB dari kosakata di atas. Jangan tulis regex.\n")
        sb.append("2. DILARANG memakai nominal atau tanggal contoh di 'when' maupun 'command'.\n")
        sb.append("   Buat kondisi dari kata ('dari', 'tujuan', 'masuk', 'keluar') atau dari rekening.\n")
        sb.append("3. command hanya boleh salah satu bentuk:\n")
        sb.append("   /tabin <id> {amount}\n")
        sb.append("   /tabout <id> {amount}\n")
        sb.append("   /pindahin {src} {dst} {amount}\n")
        sb.append("4. {src} dan {dst} memakai pemetaan rekening: ")
            .append(current.entries.joinToString(", ") { "${it.key} = ${it.value}" }).append("\n")
        sb.append("5. Kalau memang tidak boleh ada yang dicatat, pakai \"stop\": true dan \"command\": \"\".\n")
        sb.append("6. 'priority' harus unik per bank. Pindahkan nomor, jangan berbagi dengan aturan lain.\n\n")
        sb.append("ATURAN YANG SUDAH ADA:\n")
        for (r in current.rules.sortedByDescending { it.priority }) {
            sb.append("  p").append(r.priority).append(" [").append(r.bank).append("] ")
                .append(r.id).append("  when=").append(r.when.joinToString(" & "))
                .append("  -> ").append(if (r.stop) "diam" else r.command).append("\n")
        }
        sb.append("\nHASIL SEKARANG untuk notifikasi di bawah (ini yang salah):\n")
        sb.append(currentOutcome).append("\n\n")
        if (!customPrompt.isNullOrBlank()) {
            sb.append("INSTRUKSI TAMBAHAN DARI PENGGUNA (memimpa semua di atas bila bertentangan):\n")
            sb.append(customPrompt.trim()).append("\n\n")
        }
        sb.append("Notifikasi yang salah:\n")
        samples.forEachIndexed { i, n ->
            sb.append("[").append(i).append("] title=").append(n.title)
                .append("\ntext=").append(n.text).append("\nbig=").append(n.bigText ?: "").append("\n\n")
        }
        sb.append("Balas: [{\"id\":\"...\",\"name\":\"...\",\"bank\":\"bri|bca|mandiri|*\",")
            .append("\"priority\":<int>,\"enabled\":true,\"when\":[...],\"command\":\"...\",")
            .append("\"stop\":false,\"defaultId\":\"bri.ibu\"}]\n")
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
