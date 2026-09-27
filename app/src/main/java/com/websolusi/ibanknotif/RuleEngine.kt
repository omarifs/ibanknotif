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

    fun decide(parsed: Parsed, set: RuleSet): Decision {
        var considered = 0
        for (rule in set.activeFor(parsed.bank)) {
            if (!rule.matches(parsed, set.accounts)) continue
            considered++
            if (rule.stop) return Decision(null, rule, considered)
            val cmd = rule.render(parsed, set.accounts) ?: continue
            if (!isSendable(cmd)) continue
            return Decision(
                Outcome(cmd, rule.direction(), rule.id, rule.name),
                null,
                considered
            )
        }
        return Decision(null, null, considered)
    }

    /** Bentuk ringkas untuk jalur otomatis. */
    fun evaluate(parsed: Parsed, set: RuleSet): Outcome? = decide(parsed, set).outcome

    /** Gerbang terakhir sebelum dikirim ke server. Tidak ada command yang lolos tanpa ini. */
    private val SENDABLE = Regex("""^/(tabin|tabout) [a-z0-9._]{1,40} \d{1,14}$""")
    private val SENDABLE_PINDAHIN = Regex("""^/pindahin [a-z0-9._]{1,40} [a-z0-9._]{1,40} \d{1,14}$""")

    fun isSendable(command: String): Boolean =
        SENDABLE.matches(command) || SENDABLE_PINDAHIN.matches(command)

    /** Ringkasan dry-run untuk layar Aturan. */
    fun explain(parsed: Parsed, set: RuleSet): String {
        val src = parsed.source?.let { "$it -> ${set.accounts[it] ?: "?"}" } ?: "?"
        val dst = parsed.destination?.let { "$it -> ${set.accounts[it] ?: "?"}" } ?: "?"
        val head = "${parsed.bank}  Rp${parsed.amount}\n    asal    = $src\n    tujuan  = $dst"
        val d = decide(parsed, set)
        return when {
            d.outcome != null -> "$head\n    HASIL   = ${d.outcome.command}\n    aturan  = ${d.outcome.ruleName}"
            d.stoppedBy != null -> "$head\n    HASIL   = diam (sengaja)\n    aturan  = ${d.stoppedBy.name}"
            else -> "$head\n    HASIL   = tidak ada aturan yang cocok -> akan di-skip"
        }
    }
}
