package com.websolusi.ibanknotif

data class Transaction(
    val bank: String,
    val amount: Long,
    val direction: String, // "in" or "out"
    val last4: String? = null
)

object Parser {

    private val BANK_PKG = mapOf(
        "com.bca" to "bca",
        "co.id.mandiri.livin" to "mandiri",
        "co.id.mandiri.touch" to "mandiri",
        "id.co.bri.brimo" to "bri",
        "com.bri.brimo" to "bri",
        "id.co.bri.www.brimo" to "bri"
    )

    private val IN_KEYWORDS = listOf(
        "masuk", "kredit", "menerima", "terima", "diterima",
        "received", "top up", "topup", "isi saldo", "充值",
        "berhasil menerima", "incoming", "credit"
    )

    private val OUT_KEYWORDS = listOf(
        "keluar", "debit", "transfer", "bayar", "beli",
        "potong", "tagihan", "pembayaran", "withdrawal",
        "tarik", "pengiriman", "dikirim", "terkirim",
        "outgoing", "debit", "payment", "purchase"
    )

    private val AMOUNT_RE = Regex("""Rp[\s.]*(\d[\d.,]+)""", RegexOption.IGNORE_CASE)
    private val AMOUNT_NO_RP_RE = Regex("""(?:sebesar|sejumlah|nominal|rp)?\s*(\d{1,3}(?:\.\d{3})+)(?:,\d{2})?""", RegexOption.IGNORE_CASE)
    private val LAST4_RE = Regex("""[.*x*]{0,5}(\d{4})\b""")

    fun bankLabel(pkg: String): String? = BANK_PKG[pkg]

    fun parse(pkg: String, text: String): Transaction? {
        val bank = BANK_PKG[pkg] ?: return null
        return parseByBank(bank, text)
    }

    fun parseByBank(bank: String, text: String): Transaction? {
        val lower = text.lowercase()

        val amount = extractAmount(text) ?: return null
        if (amount <= 0) return null

        val direction = detectDirection(lower) ?: return null
        val last4 = extractLast4(text)

        return Transaction(bank, amount, direction, last4)
    }

    private fun extractAmount(text: String): Long? {
        val match = AMOUNT_RE.find(text)
        if (match != null) {
            val raw = match.groupValues[1]
            val cleaned = raw.replace(".", "").replace(",", "")
            return cleaned.toLongOrNull()
        }
        val match2 = AMOUNT_NO_RP_RE.find(text)
        if (match2 != null) {
            val raw = match2.groupValues[1]
            val cleaned = raw.replace(".", "")
            return cleaned.toLongOrNull()
        }
        return null
    }

    private fun detectDirection(lower: String): String? {
        for (kw in IN_KEYWORDS) {
            if (kw in lower) return "in"
        }
        for (kw in OUT_KEYWORDS) {
            if (kw in lower) return "out"
        }
        return null
    }

    private fun extractLast4(text: String): String? {
        val match = LAST4_RE.find(text) ?: return null
        return match.groupValues[1]
    }

    fun toCommand(tx: Transaction): String {
        val id = if (tx.last4 != null) "${tx.bank}.${tx.last4}" else tx.bank
        val cmd = if (tx.direction == "in") "tabin" else "tabout"
        return "/$cmd $id ${tx.amount}"
    }
}
