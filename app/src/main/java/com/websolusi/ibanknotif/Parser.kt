package com.websolusi.ibanknotif

data class Transaction(
    val bank: String,
    val amount: Long,
    val direction: String, // "in" or "out"
    val last4: String? = null,
    val name: String? = null
)

object Parser {

    private val BANK_PKG = mapOf(
        "com.bca" to "bca",
        "com.bca.mybca" to "bca",
        "co.id.mandiri.livin" to "mandiri",
        "co.id.mandiri.touch" to "mandiri",
        "id.co.bri.brimo" to "bri",
        "com.bri.brimo" to "bri",
        "id.co.bri.www.brimo" to "bri"
    )

    private val IN_KEYWORDS = listOf(
        "masuk", "kredit", "menerima", "terima", "diterima", "received",
        "top up", "topup", "isi saldo", "berhasil menerima", "incoming", "credit"
    )

    private val OUT_KEYWORDS = listOf(
        "keluar", "debit", "transfer", "bayar", "beli", "sent", "transferred",
        "potong", "tagihan", "pembayaran", "withdrawal",
        "tarik", "pengiriman", "dikirim", "terkirim",
        "outgoing", "debit", "payment", "purchase", "spent"
    )

    private val AMOUNT_RE = Regex("""(?:IDR|Rp)[\s.]*(\d[\d.,]+)""", RegexOption.IGNORE_CASE)
    private val AMOUNT_NO_RP_RE = Regex("""(?:sebesar|sejumlah|nominal|rp|idr)?\s*(\d{1,3}(?:\.\d{3})+)(?:,\d{2})?""", RegexOption.IGNORE_CASE)
    private val REKENING_RE = Regex("""rekening[:\s]*(\d{10,20})""", RegexOption.IGNORE_CASE)
    private val REKENING_MASKED_RE = Regex("""([Xx*][Xx*.]*\s*)?(\d{4})\b""")
    private val BRI_NAME_RE = Regex("""KET\.[A-Z0-9]*?_(?:\d+_)*([A-Za-z ]+)\s*$""")
    private val BRI_NAMA_AFTER_KET_RE = Regex("""KET\.[A-Z0-9_]+?\s+([A-Z][A-Za-z .]+)\s*$""")

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
        val last4 = extractLast4(bank, text)
        val name = extractName(bank, text)

        return Transaction(bank, amount, direction, last4, name)
    }

    private fun extractAmount(text: String): Long? {
        val match = AMOUNT_RE.find(text)
        if (match != null) {
            var raw = match.groupValues[1]
            raw = raw.replace(Regex("""[.,]\d{2}$"""), "")
            val cleaned = raw.replace(".", "").replace(",", "")
            return cleaned.toLongOrNull()
        }
        val match2 = AMOUNT_NO_RP_RE.find(text)
        if (match2 != null) {
            var raw = match2.groupValues[1]
            raw = raw.replace(Regex("""[.,]\d{2}$"""), "")
            val cleaned = raw.replace(".", "").replace(",", "")
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

    private fun extractLast4(bank: String, text: String): String? {
        // cari dulu nomor rekening lengkap (tampil penuh utk BRI notif masuk)
        val reken = REKENING_RE.find(text)?.groupValues?.get(1)
        if (reken != null && reken.length >= 4) return reken.takeLast(4)

        // rekening termask (XXXX...8533) utk transfer keluar/masuk via BRI
        val masked = Regex("""[Xx*].{0,3}(\d{4})\b""").find(text)?.groupValues?.get(1)
        if (masked != null) return masked

        return null
    }

    private fun extractName(bank: String, text: String): String? {
        if (bank == "bri") {
            // "KET.BRIPEN... Sobat" -> nama pemilik di akhir baris
            BRI_NAME_RE.find(text)?.let {
                val n = it.groupValues[1].trim()
                if (n.length >= 3) return n
            }
            BRI_NAMA_AFTER_KET_RE.find(text)?.let {
                val n = it.groupValues[1].trim()
                if (n.length >= 3) return n
            }
            // transfer; "dari XXXXXXXXX5530" -> "dari" lalu nama tak selalu ada, cari "Sobat"
            val m = Regex("""\bSobat\b""").find(text)
            if (m != null) return "Sobat"
        } else if (bank == "bca") {
            // "from ***PET **AK ***GSA at"
            val m = Regex("""\bfrom\s+(.+?)\s+at\b""", RegexOption.IGNORE_CASE).find(text)
            if (m != null) {
                return m.groupValues[1].trim()
            }
        }
        return null
    }

    private val PINDAH_KEYWORDS = listOf(
        "pindah saldo", "pindah dana", "transfer antarrekening", "transfer antar rekening",
        "ke rekening sendiri", "internal transfer", "own account"
    )

    fun toCommand(tx: Transaction): String {
        val id = when (tx.bank) {
            "bri" -> when (tx.last4) {
                "8533" -> "bri.ibu"
                null -> "bri"
                else -> "bri.vidha"
            }
            "bca" -> "bca"
            else -> if (tx.last4 != null) "${tx.bank}.${tx.last4}" else tx.bank
        }
        val cmd = when (tx.direction) {
            "pindahin" -> "pindahin"
            "in" -> "tabin"
            else -> "tabout"
        }
        return if (cmd == "pindahin") {
            "/pindahin $id tujuan.rek ${tx.amount}"
        } else {
            "/$cmd $id ${tx.amount}"
        }
    }
}
