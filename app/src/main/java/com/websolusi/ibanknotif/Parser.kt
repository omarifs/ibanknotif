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
 * tujuan, waktu event. Yang sering diperbaiki justru keputusan di atasnya, dan
 * itu kini ditangani [RuleEngine].
 *
 * Regex-nya sendiri berasal dari [PatternStore] supaya format notifikasi bank
 * yang baru bisa ditangani tanpa build ulang. Fungsi di sini tetap murni: tidak
 * ada jaringan, dan [PatternStore.aktif] hanya mengembalikan pola yang sudah
 * dikompilasi di dalam memori.
 */
object Parser {

    /** Ekstraksi nominal saja. */
    fun amountOf(text: String): Long? = extractAmount(text)

    fun extractSource(text: String): String? = pickLast4(PatternStore.aktif().src, text)

    fun extractDestination(text: String): String? = pickLast4(PatternStore.aktif().dst, text)

    /** 4 digit terakhir rekening yang disebut, baik penuh maupun termask. */
    fun extractLast4(text: String): String? {
        val p = PatternStore.aktif()
        val t = potong(text)
        val penuh = p.rekening.find(t)
        if (penuh != null && penuh.groupValues.size > 1 && penuh.groupValues[1].length >= 4) {
            return penuh.groupValues[1].takeLast(4)
        }
        val termask = p.maskedLast4.find(t)
        return if (termask != null && termask.groupValues.size > 1) termask.groupValues[1] else null
    }

    fun extractName(bank: String, text: String): String? {
        if (bank == "bri") {
            Regex("""KET\.[A-Z0-9]*?_(?:\d+_)*([A-Za-z ]+)\s*$""").find(text)?.let {
                val n = it.groupValues[1].trim()
                if (n.length >= 3) return n
            }
            Regex("""KET\.[A-Z0-9_]+?\s+([A-Z][A-Za-z .]+)\s*$""").find(text)?.let {
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
     * Waktu event dalam bentuk kanonik `yyyy-MM-dd HH:mm:ss`. BRImo menulisnya
     * dalam dua bentuk: `03/09/2026 07:12:49` pada notif ringkas, dan
     * `28 September 2026, 07:36:51 WIB` pada notif riwayat. Keduanya
     * dinormalkan ke satu bentuk, kalau tidak dua notif satu peristiwa punya
     * tanda tangan berbeda dan keduanya lolos -> catat ganda.
     * Null kalau tidak ada waktu event di teks.
     */
    private fun eventTs(text: String): String? {
        val t = potong(text)
        for (rx in PatternStore.aktif().eventTs) {
            val m = rx.find(t) ?: continue
            // Pola 1: grup 2 = bulan angka. Pola 2: grup 2 = nama bulan.
            val g = m.groupValues
            if (g.size < 7) continue
            val bulan = g[2].toIntOrNull() ?: PatternStore.aktif().bulan[g[2].lowercase()] ?: continue
            return "%04d-%02d-%02d %02d:%02d:%02d".format(
                g[3].toInt(), bulan, g[1].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt()
            )
        }
        return null
    }

    /**
     * Tanda tangan transaksi: bank + nominal + waktu event dari dalam teks notif.
     * Waktu event sudah dinormalkan, jadi dua notif satu peristiwa yang beda
     * format tulisannya tetap menghasilkan tanda tangan sama dan hanya satu
     * yang lolos.
     * Null kalau notif tidak memuat waktu event (myBCA), andalkan dedup notifikasi.
     */
    fun transactionSignature(bank: String, text: String, amount: Long): String? {
        val ts = eventTs(text) ?: return null
        return "tx|$bank|$amount|$ts"
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
        val t = potong(text)
        for (rx in PatternStore.aktif().amount) {
            val m = rx.find(t) ?: continue
            val cleaned = m.groupValues.drop(1).joinToString("")
                .replace(Regex("""[.,]\d{2}$"""), "")
                .replace(".", "").replace(",", "")
            cleaned.toLongOrNull()?.let { return it }
        }
        return null
    }

    private fun pickLast4(res: List<Regex>, text: String): String? {
        val t = potong(text)
        for (re in res) {
            val m = re.find(t) ?: continue
            val digits = m.groupValues.drop(1).joinToString("").filter { it.isDigit() }
            if (digits.length >= 4) return digits.takeLast(4)
        }
        return null
    }

    /**
     * Batas panjang teks sebelum dicocokkan pola. Satu-satunya penahan yang
     * berlaku untuk semua bentuk regex, termasuk pola dari server yang lolos
     * saringan [PatternStore].
     */
    private fun potong(text: String): String =
        if (text.length > PatternStore.MAX_TEXT) text.take(PatternStore.MAX_TEXT) else text
}
