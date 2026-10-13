package com.websolusi.ibanknotif

/**
 * Menerjemahkan satu notifikasi menjadi command Telegram memakai [RuleSet].
 *
 * Ini menggantikan keputusan yang sebelumnya tertanam di `Parser.detectDirection`
 * dan `Parser.accountId`. Semua pengetahuan itu kini berasal dari data, jadi
 * memperbaiki klasifikasi tidak perlu build ulang.
 */
object RuleEngine {

    data class Outcome(
        val command: String,
        val direction: String,
        val ruleId: String,
        val ruleName: String
    )

    /** Hasil lengkap: command yang jadi, atau aturan yang menyuruh diam, atau tidak ada yang cocok. */
    data class Decision(
        val outcome: Outcome?,
        val stoppedBy: Rule? = null,
        val considered: Int = 0
    )

    fun decide(parsed: Parsed, set: RuleSet, deviceDefault: String? = null): Decision {
        var considered = 0
        for (rule in set.activeFor(parsed.bank)) {
            if (!rule.matches(parsed, set.accounts)) continue
            considered++
            if (rule.stop) return Decision(null, rule, considered)
            val cmd = rule.render(parsed, set.accounts, deviceDefault) ?: continue
            if (!isSendable(cmd, set.knownIds)) continue
            return Decision(
                Outcome(cmd, rule.direction(), rule.id, rule.name),
                null,
                considered
            )
        }
        return Decision(null, null, considered)
    }

    /** Bentuk ringkas untuk jalur otomatis. */
    fun evaluate(parsed: Parsed, set: RuleSet, deviceDefault: String? = null): Outcome? =
        decide(parsed, set, deviceDefault).outcome

    /**
     * Gerbang terakhir sebelum dikirim ke server. Tidak ada command yang lolos
     * tanpa ini. Bentuk DAN nama akun keduanya diperiksa, jadi id yang tidak
     * terdaftar - dari AI, dari template belajar, atau dari ruleset yang rusak -
     * tidak pernah sampai ke server. Buktinya ada di `tele_tabungan`: ada baris
     * `tujuan rek` dan `normalisasi` yang dulu tercipta dari app versi lama.
     */
    // Verb ditulis non-tangkap `(?:...)` dengan sengaja: kalau ia ikut jadi grup,
    // indeks groupValues bergeser dan id akun dibaca sebagai "tabout". Salah sudah
    // dua kali. groupValues: 0 = seluruh perintah, 1 = id, 2 = nominal
    private val SENDABLE = Regex("""^/(?:tabin|tabout) ([a-z0-9._]{1,40}) (\d{1,14})$""")
    // 0 = seluruh perintah, 1 = id asal, 2 = id tujuan, 3 = nominal
    private val SENDABLE_PINDAHIN = Regex("""^/pindahin ([a-z0-9._]{1,40}) ([a-z0-9._]{1,40}) (\d{1,14})$""")

    fun isSendable(command: String, knownIds: Set<String> = RuleSet.KNOWN_IDS): Boolean {
        val m = SENDABLE.matchEntire(command)
        // groupValues: 0 = seluruh perintah, 1 = id, 2 = nominal
        if (m != null) return m.groupValues[1] in knownIds
        val p = SENDABLE_PINDAHIN.matchEntire(command)
        // groupValues: 0 = seluruh perintah, 1 = id asal, 2 = id tujuan, 3 = nominal
        if (p != null) return p.groupValues[1] in knownIds && p.groupValues[2] in knownIds
        return false
    }

    /** Ringkasan dry-run untuk layar Aturan. */
    fun explain(parsed: Parsed, set: RuleSet, deviceDefault: String? = null): String {
        val src = parsed.source?.let { "$it -> ${set.accounts[it] ?: "?"}" } ?: "?"
        val dst = parsed.destination?.let { "$it -> ${set.accounts[it] ?: "?"}" } ?: "?"
        val head = "${parsed.bank}  Rp${parsed.amount}\n    asal    = $src\n    tujuan  = $dst"
        val d = decide(parsed, set, deviceDefault)
        return when {
            d.outcome != null -> "$head\n    HASIL   = ${d.outcome.command}\n    aturan  = ${d.outcome.ruleName}"
            d.stoppedBy != null -> "$head\n    HASIL   = diam (sengaja)\n    aturan  = ${d.stoppedBy.name}"
            else -> "$head\n    HASIL   = tidak ada aturan yang cocok -> akan di-skip"
        }
    }
}
