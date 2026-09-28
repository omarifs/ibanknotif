package com.websolusi.ibanknotif

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pola regex untuk ekstraksi notifikasi, diserialisasi sebagai data.
 *
 * Sumbernya, berurutan dari yang paling dipercaya: `patterns.json` di server,
 * hasil tarikan terakhir, lalu [BAWAAN] di APK. Format notifikasi bank berubah
 * cukup sering, dan app ini harus bisa menanganinya tanpa build ulang.
 *
 * Dua aturan yang tidak boleh dilanggar:
 *
 * 1. **Jalur notifikasi tidak pernah menyentuh jaringan.** Pola ditransfer
 *    [terapkan] yang dipanggil dari UI atau boot, bukan dari listener
 *    notifikasi. Setelah itu, [aktif] hanya membaca objek yang sudah dikompilasi
 *    di memori, persis seperti `val Regex` biasa.
 * 2. **Pola dari server tidak dipercaya tanpa diuji.** Sebelum dipakai, pola itu
 *    dijalankan atas [PROBE] - notifikasi nyata yang tertanam di APK - dan
 *    hasilnya harus identik dengan pola bawaan. Beda satu karakter pun, pola
 *    ditolak dan yang bawaan tetap dipakai. Paritas hanya membuktikan apa yang
 *    ada di [PROBE]; pola yang benar di semua probe tapi salah pada format yang
 *    belum pernah dilihat tetap bisa lolos. Itu batasnya, dan tidak bisa dihilang.
 */
object PatternStore {

    private const val FILE = "patterns.json"
    private const val PREF = "ibanknotif"
    private const val KEY_CACHE = "patterns_cache"
    private const val KEY_VERSION = "patterns_version"
    private const val TIMEOUT_MS = 5000

    /** Panjang maksimum satu pola. Pola panjang adalah tanda pola nakal. */
    const val MAX_PATTERN = 300

    /**
     * Teks notifikasi dipotong sebelum dicocokkan. Ini satu-satunya penahan yang
     * bekerja untuk semua bentuk regex, termasuk yang lolos saringan di bawah:
     * pola Regex dari server tidak bisa dibuktikan aman, hanya dibatasi.
     */
    const val MAX_TEXT = 2000

    /**
     * Satu himpunan pola yang sudah dikompilasi. Dibuat sekali, lalu diganti
     * utuh lewat [aktif] - bukan dikompilasi ulang per notifikasi.
     */
    class Patterns(
        val version: Int,
        val amount: List<Regex>,
        val rekening: Regex,
        val maskedLast4: Regex,
        val src: List<Regex>,
        val dst: List<Regex>,
        val eventTs: List<Regex>,
        val bulan: Map<String, Int>,
        val bankPkg: Map<String, String>
    ) {
        fun last4Of(text: String, rekeningRe: Regex, maskedRe: Regex): String? {
            rekeningRe.find(text)?.let { m ->
                if (m.groupValues.size > 1 && m.groupValues[1].length >= 4) return m.groupValues[1].takeLast(4)
            }
            maskedRe.find(text)?.let { if (it.groupValues.size > 1) return it.groupValues[1] }
            return null
        }
    }

    /**
     * Nama bulan ke nomor, untuk bentuk tanggal panjang BRImo. Harus
     * dideklarasikan SEBELUM [BAWAAN]: dalam `object`, properti diinisialisasi
     * sesuai urutan deklarasi, jadi [BAWAAN] yang memakainya harus melihatnya
     * sudah terisi.
     */
    private val BULAN = mapOf(
        "jan" to 1, "januari" to 1, "feb" to 2, "februari" to 2,
        "mar" to 3, "maret" to 3, "apr" to 4, "april" to 4,
        "mei" to 5, "may" to 5, "jun" to 6, "juni" to 6,
        "jul" to 7, "juli" to 7, "agu" to 8, "agustus" to 8, "aug" to 8,
        "sep" to 9, "september" to 9, "okt" to 10, "oktober" to 10, "oct" to 10,
        "nov" to 11, "november" to 11, "des" to 12, "desember" to 12, "dec" to 12
    )

    /** Pola bawaan di dalam APK. Selalu ada, selalu bisa dipulihkan. */
    val BAWAAN: Patterns = Patterns(
        version = 0,
        amount = listOf(
            Regex("""(?:IDR|Rp)[\s.]*(\d[\d.,]+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:sebesar|sejumlah|nominal|rp|idr)?\s*(\d{1,3}(?:\.\d{3})+)(?:,\d{2})?""", RegexOption.IGNORE_CASE)
        ),
        rekening = Regex("""rekening[:\s]*(\d{10,20})""", RegexOption.IGNORE_CASE),
        maskedLast4 = Regex("""[Xx*].{0,3}(\d{4})\b"""),
        src = listOf(
            Regex(
                """dari\s+(?:rekening\s+tabungan\s+|rekening\s+|rek\.\s*)?([Xx*][Xx*.\- ]*)?(\d{4})\b""",
                RegexOption.IGNORE_CASE
            ),
            Regex("""dari\s+(?:rekening\s+)?(\d{7,20})\b""", RegexOption.IGNORE_CASE),
            Regex(
                """rekening\s+(?:asal|sumber)\s*[:\s]*([Xx*][Xx*.\- ]*)?(\d{4})\b""",
                RegexOption.IGNORE_CASE
            )
        ),
        dst = listOf(
            Regex(
                """(?:nomor\s+rekening\s+tujuan|rekening\s+tujuan|ke\s+rekening|ke\s+rek\.)[:\s]*([Xx*][Xx*.\- ]*)?(\d{4})\b""",
                RegexOption.IGNORE_CASE
            ),
            Regex("""(?:nomor\s+rekening\s+tujuan|ke\s+rekening)[:\s]*(\d{7,20})\b""", RegexOption.IGNORE_CASE)
        ),
        eventTs = listOf(
            Regex("""(\d{2})/(\d{2})/(\d{4})\s+(\d{2}):(\d{2}):(\d{2})"""),
            Regex("""(\d{1,2})\s+([A-Za-z]{3,9})\s+(\d{4})[,.]?\s+(\d{2}):(\d{2}):(\d{2})""")
        ),
        bulan = BULAN,
        bankPkg = mapOf(
            "com.bca" to "bca",
            "com.bca.mybca" to "bca",
            "com.bca.mobile" to "bca",
            "com.bca.mbs" to "bca",
            "co.id.mandiri.livin" to "mandiri",
            "co.id.mandiri.touch" to "mandiri",
            "id.co.bri.brimo" to "bri",
            "com.bri.brimo" to "bri",
            "id.co.bri.www.brimo" to "bri"
        )
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Pola yang sudah dipakai. Dibaca di jalur notifikasi, tidak ada jaringan. */
    @Volatile
    private var holder: Patterns = BAWAAN

    fun aktif(): Patterns = holder

    /**
     * Nama bank dari nama paket. Datanya dari `bankPkg` di `patterns.json`, jadi
     * aplikasi bank baru bisa ditambah dari server tanpa build. Dua salinan lama
     * di `Config` dan `Gemini` dihapus supaya tidak ada peta yang berbeda-beda.
     */
    fun bankLabel(pkg: String): String? = holder.bankPkg[pkg]

    // ---- validasi ----

    /**
     * Saringan untuk pola yang bisa membuat macet (ReDoS) pada teks panjang:
     * grup yang **mengandung** pengulang lalu **ikut** diulang, seperti
     * `(.+)+` atau `(\w*)*`. Grup berpasangan akan tertangkap, grup bertingkat
     * seperti `(\d{1,3}(?:\.\d{3})+)` tidak - dan memang tidak berbahaya.
     *
     * Biaya pemeriksaan ini adalah beberapa pola sah ikut tertolak, dan itu
     * disengaja: lebih baik ditolak daripada notifikasi bank tidak terbaca.
     * Yang berlaku untuk semua bentuk regex adalah [MAX_TEXT].
     */
    private val BERATANGKA = Regex("""\([^()]*[+*][^()]*\)[+*]""")

    private fun kompilasi(p: String, ignoreCase: Boolean): Regex? {
        if (p.isBlank()) return null
        if (p.length > MAX_PATTERN) return null
        if (BERATANGKA.containsMatchIn(p)) return null
        return try {
            // RegexOption hanya punya IGNORE_CASE dan MULTILINE - tidak ada
            // "NONE". Regex tanpa opsi memang sudah membedakan huruf besar-kecil.
            if (ignoreCase) Regex(p, RegexOption.IGNORE_CASE) else Regex(p)
        } catch (e: Exception) {
            null
        }
    }

    /** Bentuk JSON -> pola terkompilasi. Pesan kesalahan, atau null kalau sah. */
    fun parse(raw: String, hanyaBawaan: Boolean = false): Pair<Patterns?, String?> {
        return try {
            val o = JSONObject(raw)
            val version = o.optInt("version", 0)
            if (version <= 0) return null to "version harus angka bulat positif"
            if (hanyaBawaan && version > BAWAAN.version) return null to "version $version lebih baru dari bawaan"

            fun polaList(key: String, ignoreCase: Boolean): List<Regex> {
                val arr = o.optJSONArray(key) ?: return listOf()
                if (arr.length() == 0) return listOf()
                val out = ArrayList<Regex>(arr.length())
                for (i in 0 until arr.length()) {
                    val r = kompilasi(arr.getString(i), ignoreCase)
                        ?: return listOf()
                    out.add(r)
                }
                return out
            }

            val rekening = o.optString("rekening")
            val masked = o.optString("maskedLast4")
            val rekeningRe = kompilasi(rekening, true) ?: return null to "pola 'rekening' tidak bisa dipakai"
            val maskedRe = kompilasi(masked, false) ?: return null to "pola 'maskedLast4' tidak bisa dipakai"

            val amount = polaList("amount", true)
            val src = polaList("src", true)
            val dst = polaList("dst", true)
            val eventTs = polaList("eventTs", false)
            if (amount.isEmpty()) return null to "daftar 'amount' kosong atau tidak ada"
            if (src.isEmpty()) return null to "daftar 'src' kosong atau tidak ada"
            if (dst.isEmpty()) return null to "daftar 'dst' kosong atau tidak ada"
            if (eventTs.isEmpty()) return null to "daftar 'eventTs' kosong atau tidak ada"

            val bulanJson = o.optJSONObject("bulan")
            val bulan = if (bulanJson == null) BULAN else {
                val m = HashMap<String, Int>()
                bulanJson.keys().forEach { k ->
                    val n = bulanJson.optInt(k, 0)
                    if (n !in 1..12) return null to "nama bulan '$k' di luar 1-12"
                    m[k.lowercase()] = n
                }
                if (m.isEmpty()) BULAN else m
            }

            val pkgJson = o.optJSONObject("bankPkg")
            val bankPkg = if (pkgJson == null) BAWAAN.bankPkg else {
                val m = HashMap<String, String>()
                pkgJson.keys().forEach { k -> m[k] = pkgJson.getString(k) }
                if (m.isEmpty()) BAWAAN.bankPkg else m
            }

            Patterns(
                version = version,
                amount = amount,
                rekening = rekeningRe,
                maskedLast4 = maskedRe,
                src = src,
                dst = dst,
                eventTs = eventTs,
                bulan = bulan,
                bankPkg = bankPkg
            ) to null
        } catch (e: Exception) {
            null to "patterns.json tidak bisa dibaca: ${e.message}"
        }
    }

    // ---- uji paritas ----

    /** Hasil ekstraksi satu notifikasi, dipakai untuk membandingkan dua pola. */
    private fun sidik(p: Patterns, text: String): String {
        val potong = if (text.length > MAX_TEXT) text.take(MAX_TEXT) else text
        val nominal = p.amount.firstNotNullOfOrNull { rx ->
            rx.find(potong)?.let { m ->
                val c = m.groupValues.drop(1).joinToString("")
                    .replace(Regex("""[.,]\d{2}$"""), "")
                    .replace(".", "").replace(",", "")
                c.toLongOrNull()
            }
        }
        val last4 = p.last4Of(potong, p.rekening, p.maskedLast4)
        val asal = p.src.firstNotNullOfOrNull { rx -> ambil4(rx, potong) }
        val tujuan = p.dst.firstNotNullOfOrNull { rx -> ambil4(rx, potong) }
        val waktu = p.eventTs.firstNotNullOfOrNull { rx ->
            rx.find(potong)?.let { m -> m.groupValues.joinToString("|") }
        }
        return "$nominal/$last4/$asal/$tujuan/$waktu"
    }

    private fun ambil4(rx: Regex, text: String): String? {
        val m = rx.find(text) ?: return null
        val d = m.groupValues.drop(1).joinToString("").filter { it.isDigit() }
        return if (d.length >= 4) d.takeLast(4) else null
    }

    /**
     * [PROBE] harus identik antara pola bawaan dan pola kandidat. Kembalikan
     * null kalau sama, atau deskripsi kalau beda.
     */
    fun paritas(p: Patterns): String? {
        for ((nama, teks) in PROBE) {
            val a = sidik(BAWAAN, teks)
            val b = sidik(p, teks)
            if (a != b) return "probe '$nama': bawaan=$a kandidat=$b"
        }
        return null
    }

    /**
     * Notifikasi nyata yang tertanam di APK, dipindah dari `ParserTest` supaya
     * uji paritas tidak bergantung pada file test.
     */
    private val PROBE: List<Pair<String, String>> = listOf(
        "pindahin 8533 ke 5530" to
            "03/09/2026 07:12:49 - Transfer dari XXXXXXXXXX8533 dengan nomor rekening tujuan XXXXXXXXXX5530 sebesar Rp100.000,00 BERHASIL. Info lebih lanjut hubungi Call Center BRI 1500017",
        "pindahin 5530 ke 8533" to
            "03/09/2026 07:12:49 - Transfer dari XXXXXXXXXX5530 dengan nomor rekening tujuan XXXXXXXXXX8533 sebesar Rp100.000,00 BERHASIL.",
        "rekening ber-strip" to
            "05/09/2026 09:00:00 - Transfer dari XXXXX-XXXX-8533 dengan nomor rekening tujuan XXXXX-XXXX-5530 sebesar Rp250.000,00 BERHASIL.",
        "transfer masuk dari luar" to
            "31/08/2026 17:11:17 - Transfer dari XXXXXXXXXX9911 dengan nomor rekening tujuan XXXXXXXXXX8533 sebesar Rp10.000,00 BERHASIL.",
        "sobat bri" to
            "Sobat BRI! Dana Rp10.000 masuk ke rekening 000000000008533 pada 31/08/2026 17:11:17 KET:NBMB MUFIDATUL UMMA TO NURHASANAH",
        "sobat bri BRIPENSG" to
            "Sobat BRI! Dana Rp2.101.400 masuk ke rekening 000000000008533 pada 01/09/2026 02:53:33 KET:.BRIPENSG2_S_13110230200_1_9_2026",
        "top up shopeepay" to
            "02/09/2026 12:10:19 - Transaksi Top Up ShopeePay 083124253640 sebesar Rp200.000,00 BERHASIL. Info lebih lanjut hubungi Call Center BRI 1500017",
        "notif pasangan keluar" to
            "03/09/2026 07:12:49 - Dana Rp100.000 keluar dari rekening 0102001234565530",
        "mybca pengeluaran" to
            "Financial Diary You spent IDR 7,675.00 at Groceries.",
        "riwayat BRImo 3 digit" to
            "Bank BRI Top Up ShopeePay Berhasil Top Up ShopeePay Berhasil Halo, MUFIDATUL UMMAH " +
                "Berikut ini adalah informasi transaksi yang telah Anda lakukan di Aplikasi BRImo. " +
                "28 September 2026, 07:36:51 WIB Total Transaksi Rp200.000 No. Ref 220751895204 " +
                "MUFIDATUL UMMAH BANK BRI 0102 **** **** 530 ShopeePay MUFXXXXXX UMMXX 083124253640 " +
                "Nominal Rp200.000 Biaya Admin Rp0",
        "tanpa nominal" to
            "BRImo Kini, SR025 dapat dibeli di BRImo. Kupon hingga berhadiah"
    )

    // ---- tarik & pasang ----

    private fun url(ctx: Context): String {
        val ep = Config.endpoint(ctx)
        val i = ep.lastIndexOf('/')
        return (if (i > 0) ep.substring(0, i + 1) else ep) + FILE
    }

    /**
     * Tarik lalu pasang. [paksa] mengabaikan pemeriksaan versi, dipakai tombol
     * Tarik Semua. Aman dipanggil dari mana saja: fungsi ini tidak pernah
     * dipanggil dari listener notifikasi.
     */
    fun terapkan(ctx: Context, paksa: Boolean = false): String {
        val body = try {
            val c = URL(url(ctx)).openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("Accept", "application/json")
            val ok = c.responseCode in 200..299
            val isi = if (ok) c.inputStream.bufferedReader().use { it.readText() } else ""
            c.disconnect()
            if (isi.isBlank()) null else isi
        } catch (e: Exception) {
            // Jangan log pesan exception: HttpURLConnection bisa memuat URL lengkap.
            null
        }

        val mentah = body ?: prefs(ctx).getString(KEY_CACHE, null)
        if (mentah.isNullOrBlank()) {
            Config.appendLog(ctx, "POLA: tidak ada di server maupun cache, pakai bawaan")
            return "Pola notifikasi tidak bisa ditarik, memakai bawaan aplikasi."
        }

        val (p, salah) = parse(mentah)
        if (p == null) {
            Config.appendLog(ctx, "POLA ditolak: $salah")
            return "Pola dari server ditolak: $salah"
        }
        val beda = paritas(p)
        if (beda != null) {
            Config.appendLog(ctx, "POLA ditolak: paritas gagal, $beda")
            return "Pola dari server ditolak karena hasil ekstraksinya berbeda dari bawaan: $beda"
        }
        val sekarang = prefs(ctx).getInt(KEY_VERSION, 0)
        if (!paksa && p.version <= sekarang && p.version <= holder.version) {
            return "Pola di server sudah versi ${p.version}, sama dengan yang dipakai."
        }
        prefs(ctx).edit()
            .putString(KEY_CACHE, mentah)
            .putInt(KEY_VERSION, p.version)
            .apply()
        holder = p
        Config.appendLog(ctx, "POLA: versi ${p.version} aktif (paritas lolos)")
        return "Pola notifikasi versi ${p.version} dipakai."
    }

    /** Kembalikan ke pola bawaan di dalam APK. */
    fun bawaan(ctx: Context) {
        holder = BAWAAN
        prefs(ctx).edit().remove(KEY_CACHE).remove(KEY_VERSION).apply()
    }

    fun versiAktif(): Int = holder.version
}
