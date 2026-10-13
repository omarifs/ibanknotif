package com.websolusi.ibanknotif

import org.json.JSONArray
import org.json.JSONObject

/**
 * Aturan klasifikasi transaksi. Semuanya DATA, bukan kode, supaya bisa
 * diperbaiki dari dalam aplikasi (atau oleh Gemini) tanpa build ulang.
 *
 * `when` memakai kosakata tertutup [RuleSet.PREDICATES] dengan operator AND.
 * Tidak ada ekspresi bebas dan tidak ada eval, jadi mustahil ada aturan yang
 * diam-diam menulis program. OR ditulis sebagai satu token `has_any:a|b|c`.
 *
 * Aturan dengan `stop = true` sengaja tidak mengirim apa pun, dan menghentikan
 * evaluasi. Dipakai untuk kasus yang harus diam supaya notif pasangannya yang
 * mencatat — misalnya transfer ke rekening sendiri.
 */
data class Rule(
    val id: String,
    val name: String,
    val bank: String,            // "bri" | "bca" | "mandiri" | "*"
    val priority: Int,            // besar dievaluasi lebih dulu
    val enabled: Boolean,
    val conditions: List<String>,
    val command: String,          // "" kalau dipakai bersama stop = true
    val stop: Boolean = false,
    /** Dipakai untuk {id} saat rekening asal dan tujuan tidak terbaca sama sekali. */
    val defaultId: String? = null
) {
    fun matches(tx: Parsed, accounts: Map<String, String>): Boolean =
        conditions.all { RuleSet.holds(it, tx, accounts) }

    /** Ganti placeholder dengan nilai nyata. Null kalau placeholder tak terpenuhi. */
    fun render(tx: Parsed, accounts: Map<String, String>, deviceDefault: String? = null): String? {
        if (stop) return ""
        val srcId = tx.source?.let { accounts[it] }
        val dstId = tx.destination?.let { accounts[it] }
        var out = command
        // Hanya keplacing yang memang dipakai command yang wajib terpenuhi.
        if (out.contains("{src}") && srcId == null) return null
        if (out.contains("{dst}") && dstId == null) return null
        if (out.contains("{id}")) {
            val primary = (if (direction() == "in") dstId else (srcId ?: dstId))
                ?: defaultId ?: deviceDefault ?: return null
            out = out.replace("{id}", primary)
        }
        out = out.replace("{src}", srcId ?: "").replace("{dst}", dstId ?: "").replace("{amount}", tx.amount.toString())
        return if (out.contains('{')) null else out
    }

    fun direction(): String = when {
        stop -> "stop"
        command.startsWith("/pindahin") -> "pindahin"
        command.startsWith("/tabin") -> "in"
        else -> "out"
    }
}

/**
 * Parameter untuk [Pairer], tetap data supaya bisa diubah dari server tanpa build.
 *
 * Yang diatur adalah JENDELA WAKTU dan TOLERANSI FEE, bukan algoritme. Pasangan
 * hanya boleh terjadi kalau kedua sisi rekening sendiri, bank berbeda, arah
 * berlawanan, dan selisih nominal sebesar fee admin. Kalau salah satu syarat
 * tidak terpenuhi, tidak ada pasangan - kedua sisinya dikirim apa adanya.
 */
data class PairConfig(
    val enabled: Boolean = false,
    /** Berapa lama satu sisi ditahan mencari pasangannya, dalam detik. */
    val windowSec: Int = 300,
    /** Selisih nominal yang masih dibaca sebagai fee admin, dalam rupiah. */
    val feeMax: Int = 1000
) {
    val windowMs: Long get() = windowSec.toLong() * 1000L

    companion object {
        /**
         * Batas atas yang aman. Nilai di luar ini berarti file server rusak atau
         * salah kirim, dan penolakan lebih baik daripada menahan notifikasi bank
         * tanpa batas.
         */
        const val MAX_WINDOW_SEC = 3600
        const val MAX_FEE = 100_000
        fun fromJson(o: JSONObject?): PairConfig {
            if (o == null) return PairConfig()
            fun bound(raw: Int, max: Int, fallback: Int) = if (raw in 1..max) raw else fallback
            return PairConfig(
                enabled = o.optBoolean("enabled", false),
                windowSec = bound(o.optInt("windowSec", 300), MAX_WINDOW_SEC, 300),
                feeMax = bound(o.optInt("feeMax", 1000), MAX_FEE, 1000)
            )
        }

        fun toJson(c: PairConfig) = JSONObject().apply {
            put("enabled", c.enabled)
            put("windowSec", c.windowSec)
            put("feeMax", c.feeMax)
        }
    }
}

/**
 * Cara aplikasi mengirim command ke server. Data, supaya bisa diubah dari
 * server tanpa build ulang.
 *
 * Default-nya persis sama dengan angka yang dulu tertanam di [Sink], jadi
 * `rules.json` yang belum punya blok ini berperilaku seperti sebelumnya.
 */
data class DeliveryConfig(
    /** Berapa kali mencoba sebelum menyerah. 1 = tanpa retry. */
    val maxRetries: Int = 3,
    /** Jeda antar percobaan, milidetik. */
    val retryDelayMs: Int = 1500,
    /** Timeout koneksi dan baca HTTP, milidetik. */
    val timeoutMs: Int = 10000,
    /** Jeda antar POST, milidetik. Mencegah request barengan ke server. */
    val sendGapMs: Int = 500
) {
    companion object {
        const val MAX_RETRIES = 10
        const val MAX_DELAY = 60_000
        const val MAX_TIMEOUT = 60_000

        /**
         * Angka di luar batas berarti file server rusak atau salah kirim.
         * Nilainya diabaikan dan dipakai default - lebih baik daripada retry
         * tanpa batas yang membuat aplikasi tidak bisa dihentikan.
         */
        fun fromJson(o: JSONObject?): DeliveryConfig {
            if (o == null) return DeliveryConfig()
            fun b(raw: Int, max: Int, fb: Int) = if (raw in 1..max) raw else fb
            return DeliveryConfig(
                maxRetries = b(o.optInt("maxRetries", 3), MAX_RETRIES, 3),
                retryDelayMs = b(o.optInt("retryDelayMs", 1500), MAX_DELAY, 1500),
                timeoutMs = b(o.optInt("timeoutMs", 10000), MAX_TIMEOUT, 10000),
                sendGapMs = b(o.optInt("sendGapMs", 500), MAX_DELAY, 500)
            )
        }

        fun toJson(c: DeliveryConfig) = JSONObject().apply {
            put("maxRetries", c.maxRetries)
            put("retryDelayMs", c.retryDelayMs)
            put("timeoutMs", c.timeoutMs)
            put("sendGapMs", c.sendGapMs)
        }
    }
}

/**
 * Batas-batas housekeeping. Semuanya grow-only: SharedPreferences tidak
 * boleh membengkak tanpa batas di perangkat yang sering menjalankan ulang
 * prosesnya sendiri.
 */
data class RuntimeConfig(
    /** Berapa baris log yang disimpan. */
    val logLines: Int = 100,
    /** Berapa kunci anti-duplikat yang diingat. */
    val maxProcessedKeys: Int = 200
) {
    companion object {
        const val MAX_LOG_LINES = 1000
        const val MAX_KEYS = 2000

        fun fromJson(o: JSONObject?): RuntimeConfig {
            if (o == null) return RuntimeConfig()
            fun b(raw: Int, max: Int, fb: Int) = if (raw in 10..max) raw else fb
            return RuntimeConfig(
                logLines = b(o.optInt("logLines", 100), MAX_LOG_LINES, 100),
                maxProcessedKeys = b(o.optInt("maxProcessedKeys", 200), MAX_KEYS, 200)
            )
        }

        fun toJson(c: RuntimeConfig) = JSONObject().apply {
            put("logLines", c.logLines)
            put("maxProcessedKeys", c.maxProcessedKeys)
        }
    }
}

/**
 * Model Gemini yang dipakai untuk Pindai AI. Data, bukan kode, karena Google
 * mencabut nomor model tanpa pemberitahuan: `gemini-2.0-flash` mati dengan 404
 * padahal kodenya masih benar.
 *
 * Defaultnya `gemini-flash-latest` - **alias**, bukan nomor versi. Alias selalu
 * menunjuk flash stabil terbaru, jadi tidak perlu dijaga tiap kali Google
 * menaikkan major. Nomor versi yang dipatok justru yang membuat Pindai AI mati.
 */
data class AiConfig(val model: String = DEFAULT_MODEL) {
    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"

        /**
         * Bentuk model id. Dipakai sebagai potongan path URL, jadi bentuk salah
         * harus ditolak: `../` menunjuk endpoint lain dan `?key=` bisa menyelipkan
         * parameter ke URL yang sudah membawa API key. String dari server tidak
         * dipercaya tanpa ini.
         */
        val MODEL_RE = Regex("""^[a-z0-9][a-z0-9._-]{0,63}$""")

        fun fromJson(o: JSONObject?): AiConfig {
            if (o == null) return AiConfig()
            // optString() MENG-COERCE: JSON null jadi "null" dan angka jadi "123".
            // "null" lolos MODEL_RE, lalu URL jadi ".../models/null:generateContent".
            // Jadi nilai harus dipastikan String dulu, bukan opted langsung.
            val m = (o.opt("model") as? String)?.trim().orEmpty()
            return AiConfig(if (m.isNotEmpty() && MODEL_RE.matches(m)) m else DEFAULT_MODEL)
        }

        fun toJson(c: AiConfig) = JSONObject().put("model", c.model)
    }
}

/** Rekening keluarga -> id akun. Bagian dari data, bukan kode. */
data class RuleSet(
    val version: Int,
    val accounts: Map<String, String>,
    val rules: List<Rule>,
    val pairs: PairConfig = PairConfig(),
    val delivery: DeliveryConfig = DeliveryConfig(),
    val runtime: RuntimeConfig = RuntimeConfig(),
    /**
     * Model AI. Setelah [runtime] supaya pemanggilan posisional yang sudah ada
     * (tiga argumen) tetap jalan tanpa disentuh.
     */
    val ai: AiConfig = AiConfig(),
    /**
     * Id akun yang boleh muncul di `command`. Berasal dari `knownIds` di
     * `rules.json`, digabung dengan [KNOWN_IDS] bawaan. Server boleh menambah,
     * tidak bisa menghapus bawaan. Kalau `rules.json` lama tidak punya field-nya,
     * hasilnya cuma [KNOWN_IDS] - jadi file yang belum diperbarui tetap aman.
     */
    val knownIds: Set<String> = KNOWN_IDS
) {
    fun activeFor(bank: String): List<Rule> =
        rules.filter { it.enabled && (it.bank == "*" || it.bank == bank) }
            .sortedByDescending { it.priority }

    companion object {
        /** Id akun yang boleh muncul di `command`. Mencegah AI membuat akun baru. */
        val KNOWN_IDS = setOf("bri.ibu", "bri.vidha", "bca", "mandiri")

        /** Bentuk id akun yang sah, dipakai untuk menyaring daftar dari server. */
        val ID_RE = Regex("""^[a-z0-9][a-z0-9._]{0,39}$""")

        /** Kosakata `when` tanpa argumen. */
        val PREDICATES = setOf(
            "always",
            "src_known", "dst_known",
            "src_own", "dst_own", "src_external", "dst_external",
            "src_ne_dst", "src_eq_dst", "neither_account_known"
        )

        val AMOUNT_CMP = Regex("""^amount_(gt|lt):\d{1,14}$""")
        val HAS = Regex("""^(has|not):[a-z][a-z .\-]{1,40}$""")
        val KEYWORD = Regex("""^has_(any|none):[a-z][a-z .\-]{0,30}(\|[a-z][a-z .\-]{0,30})*$""")
        /**
         * Satu slot akun: boleh id literal apa saja atau placeholder. Id yang tidak
         * dikenal ditolak di [RuleValidator] dengan pesan yang menyebut idnya, bukan
         * ditolak diam-diam sebagai "command tidak cocok pola".
         */
        private const val SLOT = """(\{(id|src|dst)\}|[\w.]{1,40})"""
        val COMMAND_RE = Regex("""^/(tabin|tabout|pindahin) $SLOT( $SLOT)?( \{amount\})$""")
        val PLACEHOLDERS = setOf("id", "src", "dst", "amount")
        val WHITESPACE = Regex("""\s+""")

        /**
         * Slot akun pada sebuah command: token di antara verb dan {amount}.
         * Menghitungnya manual, bukan lewat regex, supaya /pindahin yang punya
         * dua slot ikut terhitung.
         */
        fun accountSlots(command: String): List<String> {
            val parts = command.trim().split(WHITESPACE)
            if (parts.size < 3) return emptyList()
            return parts.drop(1).dropLast(1)
        }

        /** Angka 3 digit atau lebih = nilai contoh, bukan struktur. */
        val EXAMPLE_NUMBER = Regex("""\d{3,}""")

        val VOCABULARY_HELP =
            "tanpa argumen: " + PREDICATES.joinToString(", ") +
                "\ndengan argumen: has:kata, not:kata, has_any:a|b|c, has_none:a|b|c, amount_gt:N, amount_lt:N"

        fun defaultAccounts(): Map<String, String> = mapOf(
            "8533" to "bri.ibu",
            "5530" to "bri.vidha"
        )

        /** Eksekusi satu token `when`. */
        fun holds(token: String, tx: Parsed, accounts: Map<String, String>): Boolean {
            val src = tx.source
            val dst = tx.destination
            val srcOwn = src != null && accounts.containsKey(src)
            val dstOwn = dst != null && accounts.containsKey(dst)
            return when {
                token == "always" -> true
                token == "src_known" -> src != null
                token == "dst_known" -> dst != null
                token == "src_own" -> srcOwn
                token == "dst_own" -> dstOwn
                token == "src_external" -> src != null && !srcOwn
                token == "dst_external" -> dst != null && !dstOwn
                token == "src_ne_dst" -> src != null && dst != null && src != dst
                token == "src_eq_dst" -> src != null && dst != null && src == dst
                token == "neither_account_known" -> !srcOwn && !dstOwn
                token.startsWith("has:") -> tx.text.contains(token.removePrefix("has:"), ignoreCase = true)
                token.startsWith("not:") -> {
                    val inner = token.removePrefix("not:")
                    if (inner in NEGATABLE) !holds(inner, tx, accounts)
                    else !tx.text.contains(inner, ignoreCase = true)
                }
                token.startsWith("has_any:") -> token.removePrefix("has_any:").split('|')
                    .any { tx.text.contains(it, ignoreCase = true) }
                token.startsWith("has_none:") -> token.removePrefix("has_none:").split('|')
                    .none { tx.text.contains(it, ignoreCase = true) }
                token.startsWith("amount_gt:") -> tx.amount > token.removePrefix("amount_gt:").toLong()
                token.startsWith("amount_lt:") -> tx.amount < token.removePrefix("amount_lt:").toLong()
                else -> false
            }
        }

        /** Predikat terstruktur yang boleh dir negate dengan not:. */
        private val NEGATABLE = setOf(
            "src_known", "dst_known", "src_own", "dst_own",
            "src_external", "dst_external", "src_ne_dst", "src_eq_dst"
        )

        /** Validasi satu token. Null kalau sah, selain itu pesan kesalahan. */
        fun checkToken(t: String): String? {
            if (t in PREDICATES) return null
            if (AMOUNT_CMP.matches(t)) return null
            if (t.startsWith("not:") && t.removePrefix("not:") in NEGATABLE) return null

            if (t.startsWith("has:") || t.startsWith("not:")) {
                // Angka dicek lebih dulu. Kalau setelahnya, HAS yang menolak token
                // berawalan angka dan pesan "memuat angka" tidak akan pernah muncul.
                if (EXAMPLE_NUMBER.containsMatchIn(t.substringAfter(':'))) {
                    return "kondisi '$t' memuat angka. Buat dari kata atau rekening, bukan dari nominal contoh."
                }
                if (!HAS.matches(t)) return "kondisi '$t' tidak valid. Bentuk: has:kata atau not:kata"
                return null
            }

            if (t.startsWith("has_any:") || t.startsWith("has_none:")) {
                if (EXAMPLE_NUMBER.containsMatchIn(t.substringAfter(':'))) {
                    return "kondisi '$t' memuat angka. Buat dari kata, bukan dari nominal contoh."
                }
                if (!KEYWORD.matches(t)) return "kondisi '$t' tidak valid. Bentuk: has_any:kata1|kata2"
                return null
            }

            return "kondisi '$t' tidak dikenal. Yang boleh:\n$VOCABULARY_HELP"
        }

        fun fromJson(raw: String): RuleSet? = try {
            val o = JSONObject(raw)
            val acc = LinkedHashMap<String, String>()
            o.optJSONObject("accounts")?.let { a -> a.keys().forEach { acc[it] = a.getString(it) } }
            val ids = LinkedHashSet<String>()
            o.optJSONArray("knownIds")?.let { a -> (0 until a.length()).forEach { ids.add(a.getString(it)) } }
            val arr = o.optJSONArray("rules") ?: JSONArray()
            val rules = (0 until arr.length()).mapNotNull { i ->
                val r = arr.optJSONObject(i) ?: return@mapNotNull null
                val w = r.optJSONArray("when") ?: JSONArray()
                Rule(
                    id = r.optString("id"),
                    name = r.optString("name", r.optString("id")),
                    bank = r.optString("bank", "*"),
                    priority = r.optInt("priority", 0),
                    enabled = r.optBoolean("enabled", true),
                    conditions = (0 until w.length()).map { w.getString(it) },
                    command = r.optString("command", ""),
                    stop = r.optBoolean("stop", false),
                    defaultId = r.optString("defaultId").ifBlank { null }
                )
            }.filter { it.id.isNotBlank() }
            RuleSet(
                version = o.optInt("version", 1),
                accounts = if (acc.isEmpty()) defaultAccounts() else acc,
                rules = rules,
                pairs = PairConfig.fromJson(o.optJSONObject("pairs")),
                delivery = DeliveryConfig.fromJson(o.optJSONObject("delivery")),
                runtime = RuntimeConfig.fromJson(o.optJSONObject("runtime")),
                ai = AiConfig.fromJson(o.optJSONObject("ai")),
                knownIds = KNOWN_IDS + ids.filter { it.matches(ID_RE) }
            )
        } catch (e: Exception) {
            null
        }

        fun toJson(set: RuleSet): String {
            val o = JSONObject()
            o.put("version", set.version)
            o.put("accounts", JSONObject(set.accounts as Map<*, *>))
            // Wajib ditulis: dialog Aturan menyalin JSON lewat model ini, jadi
            // field yang tidak dimodelkan akan hilang diam-diam saat diedit manual.
            o.put("knownIds", JSONArray(set.knownIds.sorted()))
            o.put("pairs", PairConfig.toJson(set.pairs))
            o.put("delivery", DeliveryConfig.toJson(set.delivery))
            o.put("runtime", RuntimeConfig.toJson(set.runtime))
            o.put("ai", AiConfig.toJson(set.ai))
            val arr = JSONArray()
            set.rules.forEach { r ->
                arr.put(JSONObject().apply {
                    put("id", r.id)
                    put("name", r.name)
                    put("bank", r.bank)
                    put("priority", r.priority)
                    put("enabled", r.enabled)
                    put("when", JSONArray(r.conditions))
                    put("command", r.command)
                    if (r.stop) put("stop", true)
                    r.defaultId?.let { put("defaultId", it) }
                })
            }
            o.put("rules", arr)
            return o.toString()
        }
    }
}

/**
 * Tolak aturan yang tidak aman sebelum disimpan.
 * Mengembalikan pesan kesalahan, atau null bila aturan layak pakai.
 */
object RuleValidator {

    fun validate(rule: Rule, set: RuleSet): String? {
        if (rule.id.isBlank()) return "id aturan kosong"
        if (!rule.id.matches(Regex("""[a-z0-9][a-z0-9-]{1,59}"""))) {
            return "id aturan harus huruf kecil, angka, dan tanda hubung"
        }
        if (set.rules.any { it.id == rule.id && it !== rule }) {
            return "id '${rule.id}' sudah dipakai"
        }
        if (rule.conditions.isEmpty()) {
            return "kondisi 'when' kosong. Pakai 'always' bila memang tanpa syarat."
        }
        for (t in rule.conditions) {
            RuleSet.checkToken(t)?.let { return it }
        }

        if (rule.stop) {
            return if (rule.command.isBlank()) null else "aturan stop tidak boleh punya command"
        }

        if (rule.command.isBlank()) return "command kosong"
        if (!RuleSet.COMMAND_RE.matches(rule.command)) {
            return "command harus '/tabin <id> {amount}', '/tabout <id> {amount}', " +
                "atau '/pindahin <id_asal> <id_tujuan> {amount}'"
        }

        val slots = RuleSet.accountSlots(rule.command)
        if (slots.isEmpty()) return "command tidak menyebut akun mana pun"
        for (slot in slots) {
            if (slot.startsWith("{") && slot.endsWith("}")) {
                val name = slot.substring(1, slot.length - 1)
                if (name !in RuleSet.PLACEHOLDERS) return "placeholder '$slot' tidak dikenal"
                continue
            }
              if (slot !in set.knownIds) {
                  return "id akun '$slot' tidak dikenal. Yang boleh: ${set.knownIds.sorted().joinToString(", ")}"
              }
          }
          if (rule.command.startsWith("/pindahin") && slots.size != 2) {
              return "/pindahin butuh dua id akun, contoh: /pindahin {src} {dst} {amount}"
          }

          rule.defaultId?.let {
              if (it !in set.knownIds) return "defaultId '$it' tidak dikenal"
          }

        val clash = set.rules.firstOrNull {
            it.id != rule.id && it.enabled && it.bank == rule.bank && it.priority == rule.priority
        }
        if (clash != null) {
            return "priority ${rule.priority} sudah dipakai '${clash.id}'. " +
                "Aturan dengan priority sama bisa saling menimpa."
        }
        return null
    }

    /** Validasi satu set penuh. Balik pesan pertama yang bermasalah, atau null. */
    fun validateAll(set: RuleSet): String? {
        for (r in set.rules) {
            validate(r, set)?.let { return "'${r.id}': $it" }
        }
        return null
    }
}
