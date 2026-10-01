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

    /**
     * Nama model **tidak** dipatok di sini. `gemini-2.0-flash` dicabut Google dan
     * Pindai AI mati dengan 404 padahal tidak ada satu baris kode yang salah.
     * Sumbernya `ai.model` di `rules.json`, jadi penggantian model cukup ubah satu
     * string di server - tanpa build ulang. [AiConfig] yang punya defaultnya.
     *
     * [RuleStore.load] tidak pernah menyentuh jaringan, jadi aman di sini: fungsi
     * ini dipanggil dari thread UI, bukan dari listener notifikasi.
     */
    private fun aiModel(ctx: Context): String = RuleStore.load(ctx).ai.model

    private fun base(model: String) =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

    private const val PROMPT_FILE = "prompt.txt"
    private const val KEY_PROMPT_CACHE = "ai_prompt_cache"
    private const val PROMPT_TIMEOUT_MS = 5000

    /**
     * Kamus + aturan untuk AI. Sumbernya, berurutan dari yang paling dipercaya:
     * `prompt.txt` di server, hasil tarikan terakhir, lalu [PROMPT_BAKAN] di APK.
     * Berubahnya prompt lalu tidak perlu build ulang, persis seperti
     * `assets/rules.json`. Sumber yang dipakai masuk log supaya versi mana yang
     * aktif kelihatan.
     */
    fun prompt(ctx: Context): String {
        fetchPrompt(ctx)?.let { return it }
        cachePrompt(ctx)?.let {
            Config.appendLog(ctx, "PROMPT: server tidak bisa dibaca, pakai hasil tarikan terakhir")
            return it
        }
        Config.appendLog(ctx, "PROMPT: pakai bawaan aplikasi")
        return PROMPT_BAKAN
    }

    private fun promptUrl(ctx: Context): String {
        val ep = Config.endpoint(ctx)
        val i = ep.lastIndexOf('/')
        return (if (i > 0) ep.substring(0, i + 1) else ep) + PROMPT_FILE
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("ibanknotif", Context.MODE_PRIVATE)

    private fun cachePrompt(ctx: Context): String? =
        prefs(ctx).getString(KEY_PROMPT_CACHE, null)?.takeIf { it.isNotBlank() }

    private fun fetchPrompt(ctx: Context): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(promptUrl(ctx)).openConnection() as HttpURLConnection
            conn.connectTimeout = PROMPT_TIMEOUT_MS
            conn.readTimeout = PROMPT_TIMEOUT_MS
            conn.setRequestProperty("Accept", "text/plain")
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) return null
            prefs(ctx).edit().putString(KEY_PROMPT_CACHE, body).apply()
            val versi = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            Config.appendLog(ctx, "PROMPT: dari server ($versi)")
            body
        } catch (_: Exception) {
            // Jangan log pesan exception: HttpURLConnection bisa memuat URL lengkap.
            null
        } finally {
            conn?.disconnect()
        }
    }

    data class RawNotif(val pkg: String, val title: String, val text: String, val bigText: String?)

    fun bankLabel(pkg: String): String? = PatternStore.bankLabel(pkg)

    /**
     * Kirim daftar notif + kamus bank + aturan format ke Gemini.
     * Return list AiResult sejajar dgn `notifs` (null utk yg gagal/tak transaksi).
     */
    fun extract(ctx: Context, apiKey: String, notifs: List<RawNotif>, customPrompt: String? = null): List<AiResult?> {
        val res = ArrayList<AiResult?>(notifs.size)
        repeat(notifs.size) { res.add(null) }

        val kamus = prompt(ctx)
        val prompt = buildPrompt(ctx, notifs, kamus, customPrompt)
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
        val model = aiModel(ctx)
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("${base(model)}?key=$apiKey").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                // Model ikut ditulis: saat model dicabut, baris ini yang mencegah
                // "kenapa gagal" jadi tebakan.
                Config.appendLog(ctx, "GEMINI ERR $code [model $model]: ${err.take(300)}")
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

    /**
     * Salinan bawaan, hanya dipakai kalau `prompt.txt` di server tidak terbaca dan
     * belum pernah ada hasil tarikan. Sengaja tidak memuat nomor rekening: peta
     * rekening disuntik dari `rules.json` (lihat [buildPrompt]) supaya tidak
     * mungkin menyimpang. Prompt pernah menulis `3508` sementara rules.json sudah
     * `5530`, dan itu persis sumber klasifikasi yang salah.
     */
    private val PROMPT_BAKAN = """
            Daftar rekening milik sendiri yang sah ditulis dari rules.json dan
            muncul tepat setelah blok ini. Yang tertulis di situ yang benar.

            Menentukan arah WAJIB dari rekening asal ("dari ...") dan tujuan
            ("nomor rekening tujuan ..." / "ke rekening ..."), BUKAN dari kata
            "pindah dana", "pindah saldo", "ke rekening sendiri", atau "dana".

            - ASAL dan TUJUAN keduanya milik sendiri, dan berbeda -> /pindahin <id_asal> <id_tujuan> <amount>
            - ASAL milik sendiri, tujuan pihak luar -> /tabout <id_asal> <amount>
            - TUJUAN milik sendiri, asal pihak luar -> /tabin <id_tujuan> <amount>
            - Hanya satu sisi yang terbaca -> JANGAN jadi tabin/tabout, kembalikan command null.
            - Tidak ada nomor rekening sama sekali -> JANGAN menebak akun mana pun,
              kembalikan command null. Mencatat ke akun yang salah lebih buruk
              daripada tidak mencatat, karena uangnya tidak bisa dikembalikan sendiri.
            - myBCA: "You spent IDR x" -> /tabout bca x ; "You received IDR x" -> /tabin bca x

            Dilarang memakai kata "Account Transfer" milik myBCA sebagai bukti transfer
            antar rekening sendiri: itu kategori transaksi biasa, id-nya "bca".

            <amount> = angka bulat rupiah TANPA titik/koma (contoh: 200000).

            JANGAN PERNAH menulis id tanpa nama rekening, misalnya "/tabout bri 200000".
            Itu akan membuat akun baru.

            KAPAN command = null: tidak ada nominal rupiah sama sekali, teks jelas
            promo/iklan/info/maintenance/saldo tampilan, ATAU nomor rekening tidak
            bisa ditentukan sehingga akun tidak bisa dipastikan. Promo produk seperti
            "SR025" dan pengumuman maintenance bank bukan transaksi.

            Contoh bentuk:
            1. Transfer dari rekening saya ke rekening saya yang lain -> /pindahin bri.ibu bri.vidha 100000
            2. Transfer dari rekening pihak luar ke rekening saya -> /tabin bri.ibu 10000
            3. Transfer dari rekening saya ke pihak luar -> /tabout bri.vidha 75000
            4. Dana masuk ke rekening saya -> /tabin bri.ibu 10000
            5. Top up e-wallet tanpa nomor rekening -> command null
            6. myBCA "You spent IDR x" -> /tabout bca x
            7. Livin "Debit Rp52.000 untuk QRIS ..." -> /tabout mandiri 52000

            pattern = <regex kotlin IGNORE_CASE yg mencocokkan bagian inti teks notif ini>.
            Untuk notif transfer, pattern WAJIB mengikat kata "dari" dan kata tujuan,
            bukan hanya nomor rekening.
            Hindari lookbehind/references complex; regex sederhana.
        """.trimIndent()

    private fun buildPrompt(
        ctx: Context,
        notifs: List<RawNotif>,
        kamus: String,
        customPrompt: String?
    ): String {
        val sb = StringBuilder()
        sb.append("Kamu ekstraktor transaksi bank. Kamus & aturan:\n").append(kamus.trim()).append("\n\n")

        // Peta rekening disuntik dari rules.json, bukan ditulis di prompt. Itu satu
        // sumber kebenaran: prompt pernah menyebut 3508 sementara rules.json sudah
        // 5530, dan angka yang salah itu ikut dipakai untuk klasifikasi.
        val set = RuleStore.load(ctx)
        sb.append("Rekening milik sendiri yang sah (dari rules.json versi ")
            .append(set.version).append("):\n")
        for ((last4, id) in set.accounts) {
            sb.append("- ").append(last4).append(" = ").append(id).append("\n")
        }
        sb.append("Semua nomor rekening lain = pihak luar.\n\n")
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
                conditions = (0 until w.length()).map { w.getString(it) },
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
        sb.append("Kamu diminta proposes SATU aturan klasifikasi baru untuk aplikasi pembaca notifikasi bank. Balas HANYA JSON array, tanpa teks lain.\n\n")
        sb.append("KOSAKATA 'when' YANG BOLEH (tidak ada yang lain, tanpa operator bebas):\n")
        sb.append("  ").append(RuleSet.PREDICATES.joinToString(", ")).append("\n")
        sb.append("  has:kata    not:kata    has_any:kata1|kata2    has_none:kata1|kata2\n")
        sb.append("  amount_gt:N   amount_lt:N\n\n")
        sb.append("id akun yang boleh muncul di command: ")
            .append(current.knownIds.sorted().joinToString(", ")).append("\n\n")
        sb.append("ATURAN WAJIB:\n")
        sb.append("1. 'when' WAJIB dari kosakata di atas. Jangan tulis regex.\n")
        sb.append("2. DILARANG memakai nominal atau tanggal contoh di 'when' maupun 'command'.\n")
        sb.append("   Buat kondisi dari kata ('dari', 'tujuan', 'masuk', 'keluar') atau dari rekening.\n")
        sb.append("3. command hanya boleh salah satu bentuk:\n")
        sb.append("   /tabin <id> {amount}\n")
        sb.append("   /tabout <id> {amount}\n")
        sb.append("   /pindahin {src} {dst} {amount}\n")
        sb.append("4. {src} dan {dst} memakai pemetaan rekening: ")
            .append(current.accounts.entries.joinToString(", ") { "${it.key} = ${it.value}" }).append("\n")
        sb.append("5. Kalau memang tidak boleh ada yang dicatat, pakai \"stop\": true dan \"command\": \"\".\n")
        sb.append("6. 'priority' harus unik per bank. Pindahkan nomor, jangan berbagi dengan aturan lain.\n")
        sb.append("7. JANGAN memakai \"defaultId\". Kalau satu sisi saja yang terbaca atau rekening\n")
        sb.append("   tidak terbaca, aturan yang benar adalah \"stop\": true, bukan menebak akun.\n\n")
        sb.append("ATURAN YANG SUDAH ADA:\n")
        for (r in current.rules.sortedByDescending { it.priority }) {
            sb.append("  p").append(r.priority).append(" [").append(r.bank).append("] ")
                .append(r.id).append("  when=").append(r.conditions.joinToString(" & "))
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
            .append("\"priority\":<int>,\"enabled\":true,\"when\":[...],")
            .append("\"command\":\"/tabin <id> {amount}\",\"stop\":false}]\n")
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
