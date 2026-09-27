package com.websolusi.ibanknotif

/**
 * Hasil ekstraksi dari teks notifikasi. Tidak ada keputusan di sini:
 * arah transaksi, pemetaan rekening, dan bentuk command semuanya datang
 * dari [RuleSet] lewat [RuleEngine].
 */
data class Parsed(
    val bank: String,
    val amount: Long,
    val source: String?,      // 4 digit rekening asal
    val destination: String?, // 4 digit rekening tujuan
    val name: String?,
    val text: String
)

/**
 * Ekstraksi primitif dari teks notifikasi: nominal, rekening asal, rekening
 * tujuan, waktu event. Regex di sini sengaja tetap di dalam kode karena ini
 * lapisan paling rendah. Yang sering diperbaiki justru keputusan di atasnya,
 * dan itu kini ditangani [RuleEngine].
 */
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

    private val AMOUNT_RE = Regex("""(?:IDR|Rp)[\s.]*(\d[\d.,]+)""", RegexOption.IGNORE_CASE)
    private val AMOUNT_NO_RP_RE = Regex("""(?:sebesar|sejumlah|nominal|rp|idr)?\s*(\d{1,3}(?:\.\d{3})+)(?:,\d{2})?""", RegexOption.IGNORE_CASE)
    private val REKENING_RE = Regex("""rekening[:\s]*(\d{10,20})""", RegexOption.IGNORE_CASE)
    private val BRI_NAME_RE = Regex("""KET\.[A-Z0-9]*?_(?:\d+_)*([A-Za-z ]+)\s*$""")
    private val BRI_NAMA_AFTER_KET_RE = Regex("""KET\.[A-Z0-9_]+?\s+([A-Z][A-Za-z .]+)\s*$""")

    private val ACCOUNT_TOKEN = """([Xx*][Xx*.\- ]*)?(\d{4})\b"""

    // Rekening ASAL: selalu mengikuti kata "dari" / "rekening asal".
    private val SRC_RES = listOf(
        Regex("""dari\s+(?:rekening\s+tabungan\s+|rekening\s+|rek\.\s*)?$ACCOUNT_TOKEN""", RegexOption.IGNORE_CASE),
        Regex("""dari\s+(?:rekening\s+)?(\d{7,20})\b""", RegexOption.IGNORE_CASE),
        Regex("""rekening\s+(?:asal|sumber)\s*[:\s]*$ACCOUNT_TOKEN""", RegexOption.IGNORE_CASE)
    )

    // Rekening TUJUAN: "nomor rekening tujuan" / "rekening tujuan" / "ke rekening".
    private val DST_RES = listOf(
        Regex(
            """(?:nomor\s+rekening\s+tujuan|rekening\s+tujuan|ke\s+rekening|ke\s+rek\.)[:\s]*$ACCOUNT_TOKEN""",
            RegexOption.IGNORE_CASE
        ),
        Regex("""(?:nomor\s+rekening\s+tujuan|ke\s+rekening)[:\s]*(\d{7,20})\b""", RegexOption.IGNORE_CASE)
    )

    // Tanggal + jam event, ada di dalam teks notif BRI. Dipakai sebagai tanda tangan transaksi.
    private val EVENT_TS_RE = Regex("""(\d{2}/\d{2}/\d{4})\s+(\d{2}:\d{2}:\d{2})""")

    fun bankLabel(pkg: String): String? = BANK_PKG[pkg]

    /** Ekstraksi nominal saja. */
    fun amountOf(text: String): Long? = extractAmount(text)

    fun extractSource(text: String): String? = pickLast4(SRC_RES, text)

    fun extractDestination(text: String): String? = pickLast4(DST_RES, text)

    /** 4 digit terakhir rekening yang disebut, baik penuh maupun termask. */
    fun extractLast4(text: String): String? {
        val reken = REKENING_RE.find(text)?.groupValues?.get(1)
        if (reken != null && reken.length >= 4) return reken.takeLast(4)
        val masked = Regex("""[Xx*].{0,3}(\d{4})\b""").find(text)?.groupValues?.get(1)
        return masked
    }

    fun extractName(bank: String, text: String): String? {
        if (bank == "bri") {
            BRI_NAME_RE.find(text)?.let {
                val n = it.groupValues[1].trim()
                if (n.length >= 3) return n
            }
            BRI_NAMA_AFTER_KET_RE.find(text)?.let {
                val n = it.groupValues[1].trim()
                if (n.length >= 3) return n
            }
            if (Regex("""\bSobat\b""").find(text) != null) return "Sobat"
        } else if (bank == "bca") {
            Regex("""\bfrom\s+(.+?)\s+at\b""", RegexOption.IGNORE_CASE).find(text)?.let {
                return it.groupValues[1].trim()
            }
        }
        return null
    }

    /**
     * Tanda tangan transaksi: bank + nominal + waktu event dari dalam teks notif.
     * Dua notif untuk satu transfer BRI memakai waktu event yang sama, jadi
     * hanya satu yang lolos -> mencegah catat ganda pindahin + tabout.
     * Null kalau notif tidak memuat waktu event (myBCA), andalkan dedup notifikasi.
     */
    fun transactionSignature(bank: String, text: String, amount: Long): String? {
        val m = EVENT_TS_RE.find(text) ?: return null
        return "tx|$bank|$amount|${m.groupValues[1]} ${m.groupValues[2]}"
    }

    /** Ubah teks notifikasi menjadi [Parsed]. Null kalau bukan transaksi (tidak ada nominal). */
    fun parseByBank(bank: String, text: String): Parsed? {
        val amount = extractAmount(text) ?: return null
        if (amount <= 0) return null
        return Parsed(
            bank = bank,
            amount = amount,
            source = extractSource(text),
            destination = extractDestination(text),
            name = extractName(bank, text),
            text = text
        )
    }

    private fun extractAmount(text: String): Long? {
        AMOUNT_RE.find(text)?.let { m ->
            val cleaned = m.groupValues[1]
                .replace(Regex("""[.,]\d{2}$"""), "")
                .replace(".", "").replace(",", "")
            cleaned.toLongOrNull()?.let { return it }
        }
        AMOUNT_NO_RP_RE.find(text)?.let { m ->
            val cleaned = m.groupValues[1]
                .replace(Regex("""[.,]\d{2}$"""), "")
                .replace(".", "").replace(",", "")
            cleaned.toLongOrNull()?.let { return it }
        }
        return null
    }

    private fun pickLast4(res: List<Regex>, text: String): String? {
        for (re in res) {
            val m = re.find(text) ?: continue
            val digits = m.groupValues.drop(1).joinToString("").filter { it.isDigit() }
            if (digits.length >= 4) return digits.takeLast(4)
        }
        return null
    }
}
