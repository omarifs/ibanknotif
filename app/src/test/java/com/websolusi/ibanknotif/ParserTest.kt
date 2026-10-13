package com.websolusi.ibanknotif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Notifikasi BRImo & myBCA di bawah ini transkrip verbatim dari tangkapan layar
 * notifikasi di dunia nyata, bukan contoh karangan.
 */
class ParserTest {

    @Test
    fun `pindahin dari 8533 ke 5530`() {
        assertEquals("8533", Parser.extractSource(PINDAHIN_8533_5530))
        assertEquals("5530", Parser.extractDestination(PINDAHIN_8533_5530))
    }

    @Test
    fun `pindahin dari 5530 ke 8533`() {
        assertEquals("5530", Parser.extractSource(PINDAHIN_5530_8533))
        assertEquals("8533", Parser.extractDestination(PINDAHIN_5530_8533))
    }

    @Test
    fun `rekening ber-strip dan ber-spasi tetap terbaca`() {
        val t = "05/09/2026 09:00:00 - Transfer dari XXXXX-XXXX-8533 dengan nomor rekening tujuan XXXXX-XXXX-5530 sebesar Rp250.000,00 BERHASIL."
        assertEquals("8533", Parser.extractSource(t))
        assertEquals("5530", Parser.extractDestination(t))
    }

    @Test
    fun `rekening penuh di Soit BRI diambil empat digit terakhir`() {
        assertEquals("8533", Parser.extractDestination(SOBAT_10000))
        assertEquals("8533", Parser.extractLast4(SOBAT_10000))
    }

    @Test
    fun `rekening tujuan fifteen digit tanpa titik masih terbaca`() {
        val t = "31/08/2026 17:11:17 - Transfer dari XXXXXXXXXX5530 dengan nomor rekening tujuan XXXXXXXXXX8533 sebesar Rp10.000,00 BERHASIL."
        assertEquals("5530", Parser.extractSource(t))
        assertEquals("8533", Parser.extractDestination(t))
    }

    @Test
    fun `nominal dengan koma dan titik`() {
        assertEquals(10000L, Parser.amountOf("sebesar Rp10.000,00"))
        assertEquals(2101400L, Parser.amountOf("Dana Rp2.101.400 masuk"))
        assertEquals(39792L, Parser.amountOf("You received IDR 39,792.00 from X"))
    }

    @Test
    fun `tanpa nominal bukan transaksi`() {
        assertNull(Parser.amountOf("BRImo Kini, SR025 dapat dibeli di BRImo"))
        assertNull(Parser.parseByBank("bri", "BRImo Kini, SR025 dapat dibeli di BRImo"))
    }

    // --- tanda tangan transaksi: dasar anti catat ganda ---

    @Test
    fun `dua notif dengan waktu event sama menghasilkan tanda tangan sama`() {
        // Pasangan notifikasi BRImo untuk satu transfer, keduanya menyebut waktu
        // event yang sama, jadi yang kedua tertahan dan tidak jadi baris ganda.
        val detail = Parser.transactionSignature("bri", PINDAHIN_8533_5530, 100000)
        val ringkas = Parser.transactionSignature("bri", SOBAT_PASANGAN_100000, 100000)
        assertNotNull(detail)
        assertNotNull(ringkas)
        assertEquals(detail, ringkas)
    }

    @Test
    fun `waktu event berbeda menghasilkan tanda tangan berbeda`() {
        // Dua notif nyata satu rekening bisa membawa waktu event yang berbeda,
        // jadi guard anti dobel tidak menahan yang kedua. Inilah alasan satu
        // top up ShopeePay bisa masuk beberapa kali sebagai beberapa baris.
        assertNotEquals(
            Parser.transactionSignature("bri", PINDAHIN_8533_5530, 100000),
            Parser.transactionSignature("bri", SOBAT_100000, 100000)
        )
    }

    @Test
    fun `notif tanpa waktu event tidak punya tanda tangan`() {
        assertNull(Parser.transactionSignature("bca", "You spent IDR 7,675.00 at Groceries.", 7675))
    }

    @Test
    fun `tanggal panjang BRImo tetap menghasilkan tanda tangan transaksi`() {
        // Dulu bentuk ini tidak dikenali EVENT_TS_RE, jadi tandanya null dan satu
        // peristiwa bisa lolos beberapa kali -> beberapa baris ganda.
        assertEquals(
            "tx|bri|200000|2026-09-28 07:36:51",
            Parser.transactionSignature("bri", TOPUP_SHOPEEPAY_RIWAYAT, 200000)
        )
    }

    @Test
    fun `dua format waktu event yang sama menghasilkan tanda tangan yang sama`() {
        val a = Parser.transactionSignature(
            "bri", "03/09/2026 07:12:49 - Transfer dari XXXXXXXXXX8533", 100000)
        val b = Parser.transactionSignature(
            "bri", "3 September 2026, 07:12:49 WIB - Transfer dari XXXXXXXXXX8533", 100000)
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(a, b)
    }

    @Test
    fun `rekening BRImo yang menampakkan tiga digit terakhir tidak terbaca`() {
        // "0102 **** **** 530" tidak punya 4 digit, jadi peta rekening tidak mungkin cocok.
        assertNull(Parser.extractSource(TOPUP_SHOPEEPAY_RIWAYAT))
        assertNull(Parser.extractDestination(TOPUP_SHOPEEPAY_RIWAYAT))
        assertNull(Parser.extractLast4(TOPUP_SHOPEEPAY_RIWAYAT))
    }

    @Test
    fun `nominal berbeda menghasilkan tanda tangan berbeda`() {
        val a = Parser.transactionSignature("bri", PINDAHIN_8533_5530, 100000)
        val b = Parser.transactionSignature("bri", PINDAHIN_8533_5530, 200000)
        assertTrue(a != b)
    }

    companion object {
        const val PINDAHIN_8533_5530 = "03/09/2026 07:12:49 - Transfer dari XXXXXXXXXX8533 dengan nomor rekening tujuan XXXXXXXXXX5530 sebesar Rp100.000,00 BERHASIL. Info lebih lanjut hubungi Call Center BRI 1500017"
        const val PINDAHIN_5530_8533 = "03/09/2026 07:12:49 - Transfer dari XXXXXXXXXX5530 dengan nomor rekening tujuan XXXXXXXXXX8533 sebesar Rp100.000,00 BERHASIL."
        const val SOBAT_10000 = "Sobat BRI! Dana Rp10.000 masuk ke rekening 000000000008533 pada 31/08/2026 17:11:17 KET:NBMB MUFIDATUL UMMA TO NURHASANAH"
        const val SOBAT_100000 = "Sobat BRI! Dana Rp100.000 masuk ke rekening 000000000008533 pada 31/08/2026 17:11:17 KET:X"

        /**
         * Bentuk BRImo, dikarang supaya waktu eventnya sama dengan
         * [PINDAHIN_8533_5530] supaya pasangan notifikasi satu transfer bisa
         * diuji. Bukan transkrip tangkapan layar.
         */
        const val SOBAT_PASANGAN_100000 = "Sobat BRI! Dana Rp100.000 masuk ke rekening 000000000008533 pada 03/09/2026 07:12:49 KET:X"

        /**
         * Notifikasi BRImo bentuk kaya, transkrip dari tangkapan layar HP vidha.
         * Satu cacat notifikasi ini yang tidak bisa diatasi oleh aturan: rekening
         * `0102 **** **** 530` hanya menampakkan TIGA digit terakhir, sedangkan
         * peta rekening memakai empat. Jadi tidak ada akun yang bisa ditentukan
         * dari angkanya.
         *
         * Yang tersisa cuma nama pemilik di notif, dan itu yang dipakai aturan
         * `bri-ewallet-tanpa-rekening-pemilik-vidha`. Bukan `defaultId`, yang 5L
         * tolak justru karena menebak benar di satu HP dan pasti salah di HP lain.
         *
         * Waktu eventnya ditulis panjang (`28 September 2026, 07:36:51 WIB`);
         * itu ditangani di [Parser.transactionSignature] dengan menormalkan ke
         * satu bentuk kanonik.
         */
        const val TOPUP_SHOPEEPAY_RIWAYAT =
            "Bank BRI Top Up ShopeePay Berhasil Top Up ShopeePay Berhasil " +
                "Halo, MUFIDATUL UMMAH " +
                "Berikut ini adalah informasi transaksi yang telah Anda lakukan di Aplikasi BRImo. " +
                "28 September 2026, 07:36:51 WIB " +
                "Top Up ShopeePay Berhasil " +
                "Total Transaksi Rp200.000 " +
                "No. Ref 220751895204 " +
                "MUFIDATUL UMMAH " +
                "BANK BRI 0102 **** **** 530 " +
                "ShopeePay MUFXXXXXX UMMXX 083124253640 " +
                "Jenis Transaksi ShopeePay " +
                "Catatan - " +
                "Nominal Rp200.000 " +
                "Biaya Admin Rp0 " +
                "Total Transaksi Rp200.000"

        /**
         * Bentuk BRImo untuk QRIS transfer: asal rekening disebut, rekening
         * tujuan tidak. Tanpa aturan `bri-qris-transfer-dana-keluar` (prio 92)
         * notif ini tertahan aturan diam prio 80, padahal tidak ada notif
         * pasangan dari sisi penerima seperti pada transfer antar rekening.
         *
         * **INI KARANGAN, bukan transkrip tangkapan layar.** Bentuk aslinya belum
         * pernah dilihat. Ganti dengan teks asli begitu notif QRIS sungguhan
         * muncul di log - contoh karangan tidak menangkap variasi yang sebenarnya
         * terjadi, dan itu sebabnya [TOPUP_SHOPEEPAY_RIWAYAT] di atas perlu
         * transkrip asli.
         */
        const val QRIS_TRANSFER_KARANGAN =
            "05/10/2026 10:21:18 - Transfer QRIS ke TOKO SEJAHTERA Berhasil. " +
                "Nominal Rp75.000 dari rekening 0102001234568533"

        /**
         * Bentuk BRImo yang sama untuk rekening ibu: `0102 **** **** 533` adalah
         * tiga digit terakhir dari 8533, dan pem pilotedirect-nya adalah
         * "NURHASANAH" (lihat `LOG-PROYEK.md` 5L: notif dengan `KET: NBMB
         * MUFIDATUL UMMA TO NURHASANAH` tercatat sebagai `/tabin bri.ibu`).
         *
         * **INI TURUNAN dari [TOPUP_SHOPEEPAY_RIWAYAT], bukan transkrip tangkapan
         * layar HP ibu.** Yang diganti hanya nama dan tiga digit rekeningnya.
         * Bentuk BRImo yang lain belum pernah terlihat di HP itu.
         */
        const val TOPUP_IBU_RIWAYAT =
            "Bank BRI Top Up ShopeePay Berhasil Top Up ShopeePay Berhasil " +
                "Halo, NURHASANAH " +
                "Berikut ini adalah informasi transaksi yang telah Anda lakukan di Aplikasi BRImo. " +
                "05 Oktober 2026, 10:21:18 WIB " +
                "Top Up ShopeePay Berhasil " +
                "Total Transaksi Rp250.000 " +
                "No. Ref 225768447801 " +
                "NURHASANAH " +
                "BANK BRI 0102 **** **** 533 " +
                "ShopeePay NURXXXXXX ANXX 081234567890 " +
                "Jenis Transaksi ShopeePay " +
                "Catatan - " +
                "Nominal Rp250.000 " +
                "Biaya Admin Rp0 " +
                "Total Transaksi Rp250.000"
    }
}

/**
 * Aturan bawaan di `assets/rules.json` harus menghasilkan command yang sama
 * persis dengan logika yang sebelumnya tertanam di `Parser`. Inilah yang
 * menjamin refactor jadi-rules tidak mengubah perilaku sama sekali.
 */
class RuleEngineTest {

    private val rules: RuleSet = loadBundled()

    private fun loadBundled(): RuleSet {
        val f = File("src/main/assets/rules.json")
        assertTrue("assets/rules.json tidak ditemukan di ${f.absolutePath}", f.exists())
        return RuleSet.fromJson(f.readText())
            ?: throw AssertionError("assets/rules.json tidak bisa di-parse")
    }

    private fun run(bank: String, text: String): String? =
        Parser.parseByBank(bank, text)?.let { RuleEngine.evaluate(it, rules) }?.command

    @Test
    fun `pindahin dari 8533 ke 5530`() {
        assertEquals("/pindahin bri.ibu bri.vidha 100000", run("bri", ParserTest.PINDAHIN_8533_5530))
    }

    @Test
    fun `pindahin dari 5530 ke 8533`() {
        assertEquals("/pindahin bri.vidha bri.ibu 100000", run("bri", ParserTest.PINDAHIN_5530_8533))
    }

    @Test
    fun `pindahin dengan rekening ber-strip`() {
        val t = "05/09/2026 09:00:00 - Transfer dari XXXXX-XXXX-8533 dengan nomor rekening tujuan XXXXX-XXXX-5530 sebesar Rp250.000,00 BERHASIL."
        assertEquals("/pindahin bri.ibu bri.vidha 250000", run("bri", t))
    }

    @Test
    fun `transfer ke rekening sendiri tidak dicatat`() {
        val t = "06/09/2026 10:00:00 - Transfer dari XXXXXXXXXX8533 dengan nomor rekening tujuan XXXXXXXXXX8533 sebesar Rp50.000,00 BERHASIL."
        assertNull(run("bri", t))
    }

    @Test
    fun `transfer masuk dari rekening luar bukan pindahin`() {
        val t = "31/08/2026 17:11:17 - Transfer dari XXXXXXXXXX9911 dengan nomor rekening tujuan XXXXXXXXXX8533 sebesar Rp10.000,00 BERHASIL."
        assertEquals("/tabin bri.ibu 10000", run("bri", t))
    }

    @Test
    fun `transfer dari rekening vidha ke ibu jadi pindahin bukan pemasukan`() {
        // 5530 dulu dianggap rekening luar, jadi uang yang keluar dari vidha
        // tercatat sebagai pemasukan ibu tanpa mengurangi saldo vidha.
        assertEquals("/pindahin bri.vidha bri.ibu 100000", run("bri", ParserTest.PINDAHIN_5530_8533))
    }

    @Test
    fun `transfer keluar ke rekening luar bukan pindahin`() {
        val t = "31/08/2026 17:11:17 - Transfer dari XXXXXXXXXX5530 dengan nomor rekening tujuan XXXXXXXXXX9911 sebesar Rp75.000,00 BERHASIL."
        assertEquals("/tabout bri.vidha 75000", run("bri", t))
    }

    @Test
    fun `sobat bri dana masuk`() {
        assertEquals("/tabin bri.ibu 10000", run("bri", ParserTest.SOBAT_10000))
    }

    @Test
    fun `sobat bri dengan kode keterangan BRIPENSG`() {
        val t = "Sobat BRI! Dana Rp2.101.400 masuk ke rekening 000000000008533 pada 01/09/2026 02:53:33 KET:.BRIPENSG2_S_13110230200_1_9_2026"
        assertEquals("/tabin bri.ibu 2101400", run("bri", t))
    }

    @Test
    fun `top up shopeepay tanpa rekening tidak dicatat`() {
        // Tidak ada nomor rekening di notif, jadi tidak ada yang bisa menentukan
        // akun mana. Dulu rule ini memakai defaultId "bri.ibu", jadi uang yang
        // keluar dari HP vidha tercatat sebagai pengeluaran ibu.
        val t = "02/09/2026 12:10:19 - Transaksi Top Up ShopeePay 083124253640 sebesar Rp200.000,00 BERHASIL. Info lebih lanjut hubungi Call Center BRI 1500017"
        assertNull(run("bri", t))
    }

    @Test
    fun `top up shopeepay tanpa rekening pakai akun default perangkat`() {
        // Notif pendek ini tidak memuat rekening maupun nama, jadi satu-satunya
        // pembeda tinggal perangkat. Akun default disimpan per-HP, bukan di
        // rules.json, supaya tarikan aturan dari server tetap jalan.
        val t = "02/09/2026 12:10:19 - Transaksi Top Up ShopeePay 083124253640 sebesar Rp200.000,00 BERHASIL. Info lebih lanjut hubungi Call Center BRI 1500017"
        val parsed = Parser.parseByBank("bri", t)!!
        assertEquals("/tabout bri.ibu 200000", RuleEngine.evaluate(parsed, rules, "bri.ibu")?.command)
        assertEquals("/tabout bri.vidha 200000", RuleEngine.evaluate(parsed, rules, "bri.vidha")?.command)
    }

    @Test
    fun `akun default perangkat tidak menimpa notif yang rekeningnya terbaca`() {
        val parsed = Parser.parseByBank("bri", ParserTest.PINDAHIN_8533_5530)!!
        assertEquals("/pindahin bri.ibu bri.vidha 100000", RuleEngine.evaluate(parsed, rules, "bri.vidha")?.command)
    }

    @Test
    fun `notif riwayat BRImo tanpa rekening tapi namanya vidha jadi tabout vidha`() {
        // Rekening `0102 **** **** 530` cuma menampakkan tiga digit, jadi peta
        // rekening 4 digit tidak mungkin cocok. Satu-satunya bukti yang tersisa
        // adalah nama pemilik di notif, jadi itu yang jadi penentu.
        assertEquals("/tabout bri.vidha 200000", run("bri", ParserTest.TOPUP_SHOPEEPAY_RIWAYAT))
    }

    @Test
    fun `nama vidha tidak membayangi aturan yang rekeningnya terbaca`() {
        // Penjaga `neither_account_known`. Tanpa penjaga ini, notif yang menyebut
        // rekening sendiri akan ditulis ulang jadi pengeluaran vidha.
        val t = "03/09/2026 07:12:49 - Transfer dari XXXXXXXXXX5530 dengan nomor " +
            "rekening tujuan XXXXXXXXXX8533 sebesar Rp100.000,00 BERHASIL. Halo, MUFIDATUL UMMAH"
        assertEquals("/pindahin bri.vidha bri.ibu 100000", run("bri", t))
    }

    @Test
    fun `dana masuk e-wallet tanpa rekening tetap diam bukan jadi tabout`() {
        // Penjaga `has_none`. Refund atau pencairan ShopeePay akan salah arah -
        // tercatat sebagai pengeluaran - kalau ikut kena aturan yang sama.
        val t = "Halo, MUFIDATUL UMMAH Dana Rp200.000 masuk dari ShopeePay pada " +
            "05/10/2026 10:21:18 KET:X"
        assertNull(run("bri", t))
    }

    @Test
    fun `qris transfer dari rekening sendiri jadi tabout bukan tertahan diam`() {
        // Tanpa aturan prio 92, notif ini tertahan `bri-transfer-tanpa-tujuan-dibaca-diam`
        // (prio 80) karena asalnya rekening sendiri tapi tujuan tidak disebut.
        // QRIS tidak punya notif pasangan dari sisi penerima, jadi tidak ada yang
        // akan mencatat kalau aturannya diam.
        assertEquals("/tabout bri.ibu 75000", run("bri", ParserTest.QRIS_TRANSFER_KARANGAN))
    }

    @Test
    fun `qris transfer dari rekening vidha tetap milik vidha`() {
        val t = "05/10/2026 10:21:18 - Transfer QRIS ke TOKO SEJAHTERA Berhasil. " +
            "Nominal Rp75.000 dari rekening 0102001234565530"
        assertEquals("/tabout bri.vidha 75000", run("bri", t))
    }

    @Test
    fun `qris masuk dari luar bukan pengeluaran`() {
        val t = "05/10/2026 10:21:18 - Dana Rp75.000 masuk dari QRIS ke rekening " +
            "000000000008533 BERHASIL."
        assertEquals("/tabin bri.ibu 75000", run("bri", t))
    }

    @Test
    fun `transfer biasa tanpa tujuan tetap diam agar tidak dobel`() {
        // Penjaga aturan prio 92: bukan QRIS, jadi aturan diam prio 80 tetap
        // memegang. Inilah yang mencegah satu transfer jadi /pindahin + /tabout.
        val t = "05/10/2026 10:21:18 - Transfer dari rekening 0102001234568533 " +
            "sebesar Rp75.000,00 BERHASIL."
        assertNull(run("bri", t))
    }

    @Test
    fun `mybca pengeluaran`() {
        assertEquals("/tabout bca 7675", run("bca", "Financial Diary You spent IDR 7,675.00 at Groceries."))
    }

    @Test
    fun `mybca kategori account transfer tetap pengeluaran bukan pindahin`() {
        assertEquals("/tabout bca 8000", run("bca", "Financial Diary You spent IDR 8,000.00 at Account Transfer."))
    }

    @Test
    fun `mybca pemasukan dengan kategori account transfer tetap pemasukan`() {
        val t = "Financial Diary You received IDR 39,792.00 from ***PET **AK ***GSA at Account Transfer category."
        assertEquals("/tabin bca 39792", run("bca", t))
    }

    @Test
    fun `promo bank tidak dicatat`() {
        assertNull(run("bri", "BRImo Kini, SR025 dapat dibeli di BRImo. Kupon hingga berhadiah"))
    }

    @Test
    fun `info maintenance tidak dicatat`() {
        assertNull(run("bca", "myBCA BCA mobile, KlikBCA and ATM Service Maintenance Information BCA e-Banking"))
    }

    @Test
    fun `transfer masuk tanpa asal terbaca diam agar tidak dobel`() {
        val t = "03/09/2026 07:12:49 - Transfer masuk ke rekening 000000000008533 Rp100.000,00 BERHASIL."
        assertNull(run("bri", t))
    }

    @Test
    fun `struk BRImo tanpa rekening tapi namanya ibu jadi tabout ibu`() {
        // Simetrisnya aturan vidha, untuk rekening 8533.
        assertEquals("/tabout bri.ibu 250000", run("bri", ParserTest.TOPUP_IBU_RIWAYAT))
    }

    @Test
    fun `nama ibu tanpa sapaan halo tetap diam`() {
        // Penjaga `has:halo`. `KET: NBMB MUFIDATUL UMMA TO NURHASANAH` memuat
        // KEDUA nama pemilik, jadi nama saja tidak bisa membedakan whose notif.
        // Sapaan "Halo, ..." di struk BRImo yang memisahkan keduanya.
        val t = "Top Up ShopeePay atas nama NURHASANAH Rp200.000 pada " +
            "05/10/2026 10:21:18"
        assertNull(run("bri", t))
    }

    @Test
    fun `ket yang memuat dua nama tidak menghasilkan apa pun`() {
        // Notif transfer antar rekening yang field KET-nya memuat pengirim dan
        // penerima sekaligus. Rekeningnya tidak terbaca di sini, jadi penjaga
        // `has:halo` satu-satunya yang menahannya - dan itu memang tujuannya.
        val t = "Halo, MUFIDATUL UMMAH Dana Rp100.000 KET:NBMB MUFIDATUL UMMA TO " +
            "NURHASANAH pada 05/10/2026 10:21:18"
        assertNull(run("bri", t))
    }

    @Test
    fun `ewallet dengan rekening asal terbaca tidak butuh nama`() {
        // Lapis kedua dari yang berlaku: kalau notif menyebut rekening asal, akun
        // ikut terbaca dari `accounts` dan tidak ada tebakan sama sekali.
        // Aturan 92 yang bekerja di sini, bukan yang berbasis nama.
        val t = "05/10/2026 10:21:18 - Top Up ShopeePay Berhasil. " +
            "Nominal Rp120.000 dari rekening 0102001234565530"
        assertEquals("/tabout bri.vidha 120000", run("bri", t))
    }

    @Test
    fun `tiga rumusan QRIS berbeda semuanya jadi pengeluaran`() {
        // Fixture aslinya masih karangan (lihat QRIS_TRANSFER_KARANGAN). Yang
        // diuji di sini justru daftar kata kuncinya: selama notif memuat salah
        // satu dari kata itu, aturan 92 tetap kena tanpa perlu tahu rumusannya.
        val heads = listOf(
            "Transfer QRIS ke TOKO SEJAHTERA Berhasil.",
            "Pembayaran QRIS Berhasil.",
            "Scan QRIS Berhasil."
        )
        for (h in heads) {
            val t = "05/10/2026 10:21:18 - $h Nominal Rp75.000 dari rekening 0102001234568533"
            assertEquals("gagal pada: $h", "/tabout bri.ibu 75000", run("bri", t))
        }
    }

    @Test
    fun `notif pasangan transfer dari rekening sendiri tetap dicatat sebagai keluar`() {
        val t = "03/09/2026 07:12:49 - Dana Rp100.000 keluar dari rekening 0102001234565530"
        assertEquals("/tabout bri.vidha 100000", run("bri", t))
    }

    @Test
    fun `notif pasangan transfer dari rekening yang bukan milik kita diam`() {
        // 3508 itu rekening Flip, bukan rekening keluarga.
        val t = "03/09/2026 07:12:49 - Dana Rp100.000 keluar dari rekening 4737010273673508"
        assertNull(run("bri", t))
    }

    @Test
    fun `semua aturan bawaan lolos validasi`() {
        assertNull(RuleValidator.validateAll(rules))
    }
}

/** Aturan dari AI harus ditolak kalau tidak aman. Ini yang menjaga agar tidak ada build ulang. */
class RuleValidatorTest {

    private fun base() = RuleSet(1, RuleSet.defaultAccounts(), emptyList())

    private fun rule(
        id: String = "uji",
        when_: List<String> = listOf("src_own"),
        command: String = "/tabout {id} {amount}",
        priority: Int = 50,
        stop: Boolean = false
    ) = Rule(
            id = id, name = "uji", bank = "bri", priority = priority, enabled = true,
            conditions = when_, command = command, stop = stop
        )

    @Test
    fun `aturan kosong ditolak`() {
        assertNotNull(RuleValidator.validate(rule(when_ = emptyList()), base()))
    }

    @Test
    fun `kondisi di luar kosakata ditolak`() {
        val e = RuleValidator.validate(rule(when_ = listOf("src_own", "teks =~ /dari/")), base())
        assertNotNull(e)
    }

    @Test
    fun `kondisi yang terikat nominal contoh ditolak`() {
        val e = RuleValidator.validate(rule(when_ = listOf("has:300000")), base())
        assertNotNull(e)
        assertTrue(e!!.contains("angka"))
    }

    @Test
    fun `command dengan nominal tetap ditolak`() {
        val e = RuleValidator.validate(rule(command = "/tabout bri.ibu 300000"), base())
        assertNotNull(e)
    }

    @Test
    fun `id akun yang tidak dikenal ditolak`() {
        val e = RuleValidator.validate(rule(command = "/tabout bni.baru {amount}"), base())
        assertNotNull(e)
        assertTrue(e!!.contains("tidak dikenal"))
    }

    @Test
    fun `pindahin kurang dari dua id ditolak`() {
        val e = RuleValidator.validate(rule(command = "/pindahin {src} {amount}"), base())
        assertNotNull(e)
    }

    @Test
    fun `aturan yang tidak bisa diubah hanya dalam kode tetap bisa diganti lewat data`() {
        // Inilah tujuan seluruh desain: memperbaiki klasifikasi tanpa build.
        val baru = rule(
            id = "bri-pindahin-antar-rekening-sendiri",
            when_ = listOf("src_own", "dst_own", "src_ne_dst"),
            command = "/pindahin {src} {dst} {amount}",
            priority = 100
        )
        val set = RuleSet(1, RuleSet.defaultAccounts(), listOf(baru))
        assertNull(RuleValidator.validate(baru, set))
        val parsed = Parser.parseByBank("bri", ParserTest.PINDAHIN_8533_5530)!!
        assertEquals("/pindahin bri.ibu bri.vidha 100000", RuleEngine.evaluate(parsed, set)?.command)
    }

    @Test
    fun `aturan stop tidak boleh punya command`() {
        val e = RuleValidator.validate(rule(stop = true, command = "/tabout {id} {amount}"), base())
        assertNotNull(e)
    }

    @Test
    fun `priority yang sama untuk bank yang sama ditolak`() {
        val a = rule(id = "satu", priority = 70)
        val set = RuleSet(1, RuleSet.defaultAccounts(), listOf(a))
        val e = RuleValidator.validate(rule(id = "dua", priority = 70), set)
        assertNotNull(e)
        assertTrue(e!!.contains("priority"))
    }

    @Test
    fun `aturan valid lolos`() {
        val good = rule(
            id = "bri-dana-keluar-dari-rekening-sendiri",
            when_ = listOf("src_own", "dst_external"),
            command = "/tabout {id} {amount}",
            priority = 91
        )
        assertNull(RuleValidator.validate(good, base()))
    }
}

/**
 * Id akun yang boleh muncul di command, sekarang bisa ditambah dari
 * `rules.json` tanpa build. Server boleh menambah, tidak bisa menghapus bawaan.
 */
class KnownIdsTest {

    @Test
    fun `knownIds dari server digabung dengan bawaan kode`() {
        val json = """
            {"version":1,"accounts":{"8533":"bri.ibu"},"knownIds":["bri.ibu","bca.ayah"],"rules":[]}
        """.trimIndent()
        val set = RuleSet.fromJson(json)!!
        assertTrue("bri.vidha" in set.knownIds)
        assertTrue("mandiri" in set.knownIds)
        assertTrue("bca.ayah" in set.knownIds)
    }

    @Test
    fun `rules json tanpa knownIds aman dan hanya memuat bawaan`() {
        val set = RuleSet.fromJson("""{"version":2,"accounts":{"8533":"bri.ibu"},"rules":[]}""")!!
        assertEquals(RuleSet.KNOWN_IDS, set.knownIds)
    }

    @Test
    fun `id dengan bentuk salah dibuang dari daftar server`() {
        val json = """
            {"version":1,"accounts":{},"knownIds":["bca.ayah","ADA CAPS","x y","-huruf",""],"rules":[]}
        """.trimIndent()
        val set = RuleSet.fromJson(json)!!
        assertTrue("bca.ayah" in set.knownIds)
        assertFalse("ADA CAPS" in set.knownIds)
        assertFalse("x y" in set.knownIds)
    }

    @Test
    fun `knownIds tidak hilang saat json dibolak balik lewat editor`() {
        // Dialog Aturan menyalin JSON lewat RuleSet, jadi field yang tidak
        // dimodelkan akan hilang diam-diam setiap kali pengguna mengedit manual.
        val asli = RuleSet.fromJson("""{"version":1,"accounts":{},"knownIds":["bca.ayah"],"rules":[]}""")!!
        val bolak = RuleSet.fromJson(RuleSet.toJson(asli))!!
        assertTrue("bca.ayah" in bolak.knownIds)
    }

    @Test
    fun `id dari server boleh dipakai di aturan`() {
        val json = """
            {"version":1,"accounts":{"9911":"bca.ayah"},
             "knownIds":["bca.ayah"],
             "rules":[{"id":"bca-ayah","name":"ayah","bank":"bca","priority":10,
                       "when":["src_own"],"command":"/tabout bca.ayah {amount}"}]}
        """.trimIndent()
        val set = RuleSet.fromJson(json)!!
        assertNull(RuleValidator.validateAll(set))
    }

    @Test
    fun `isSendable menolak id yang tidak terdaftar`() {
        val ids = RuleSet.KNOWN_IDS
        assertTrue(RuleEngine.isSendable("/tabout bri.ibu 200000", ids))
        assertTrue(RuleEngine.isSendable("/pindahin bri.ibu bri.vidha 100000", ids))
        assertFalse(RuleEngine.isSendable("/tabout bni.baru 200000", ids))
        assertFalse(RuleEngine.isSendable("/tabout tujuan.rek 200000", ids))
        assertFalse(RuleEngine.isSendable("/pindahin bri.ibu bni.baru 100000", ids))
        assertFalse(RuleEngine.isSendable("/tabout bri.ibu 200000 /pindahin", ids))
    }

    @Test
    fun `isSendable menerima id yang ditambahkan dari server`() {
        val ids = RuleSet.KNOWN_IDS + "bca.ayah"
        assertTrue(RuleEngine.isSendable("/tabout bca.ayah 200000", ids))
    }
}

/**
 * Pola notifikasi came from server as data. Yang menguji keamanannya di sini
 * adalah [PatternStore.paritas]: pola dari server tidak boleh dipakai kalau
 * hasilnya berbeda dari pola bawaan pada notifikasi nyata.
 */
class PatternStoreTest {

    private fun bundled(): String {
        val f = File("src/main/assets/patterns.json")
        assertTrue("assets/patterns.json tidak ditemukan di ${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `pola bawaan di assets valid dan version positif`() {
        val (p, e) = PatternStore.parse(bundled())
        assertNull(e)
        assertNotNull(p)
        assertEquals(1, p!!.version)
    }

    @Test
    fun `pola bawaan lolos paritas terhadap dirinya sendiri`() {
        val (p, e) = PatternStore.parse(bundled())
        assertNull(e)
        assertNull(PatternStore.paritas(p!!))
    }

    @Test
    fun `perubahan pola yang benar ditolak oleh paritas`() {
        // Menambah satu pola rekening yang lebih longgar akan mengubah hasil
        // ekstraksi pada notif BRImo - itu harus ketahuan, bukan diam saja.
        val diubah = bundled().replace(
            """"maskedLast4": "[Xx*].{0,3}(\\d{4})\\b"""",
            """"maskedLast4": "[Xx*].*?(\\d{4})\\b""""
        )
        assertTrue("pola tidak ketemu, tes ini tidak menguji apa pun", diubah != bundled())
        val (p, e) = PatternStore.parse(diubah)
        assertNull(e)
        assertNotNull("pola seharusnya tetap bisa dikompilasi", p)
        val beda = PatternStore.paritas(p!!)
        assertNotNull("paritas harus gagal", beda)
        assertTrue(beda!!.contains("probe"))
    }

    @Test
    fun `pola yang gagal compile ditolak`() {
        val rusak = bundled().replace("\"version\": 1", "\"version\": 1, \"rekening\": \"((\"")
        val (p, e) = PatternStore.parse(rusak)
        assertNull(p)
        assertNotNull(e)
        assertTrue(e!!.contains("rekening"))
    }

    @Test
    fun `pola dengan pengulang bersarang ditolak`() {
        val nakal = bundled().replace("\"version\": 1", "\"version\": 1, \"rekening\": \"(.+)+\"")
        val (p, e) = PatternStore.parse(nakal)
        assertNull(p)
        assertNotNull(e)
    }

    @Test
    fun `pola terlalu panjang ditolak`() {
        val panjang = "(?:a|b|" + "c".repeat(PatternStore.MAX_PATTERN) + ")"
        val nakal = bundled().replace("\"version\": 1", "\"version\": 1, \"rekening\": \"$panjang\"")
        val (p, _) = PatternStore.parse(nakal)
        assertNull(p)
    }

    @Test
    fun `daftar wajib yang kosong ditolak`() {
        val kosong = bundled().replace("\"version\": 1", "\"version\": 1, \"src\": []")
        val (p, e) = PatternStore.parse(kosong)
        assertNull(p)
        assertTrue(e!!.contains("src"))
    }

    @Test
    fun `version harus positif`() {
        val (p, e) = PatternStore.parse("""{"version":0,"amount":[],"src":[],"dst":[],"eventTs":[]}""")
        assertNull(p)
        assertNotNull(e)
    }

    @Test
    fun `nama bank dibaca dari bankPkg`() {
        assertEquals("bri", PatternStore.bankLabel("id.co.bri.brimo"))
        assertEquals("bca", PatternStore.bankLabel("com.bca.mybca"))
        assertEquals("mandiri", PatternStore.bankLabel("co.id.mandiri.livin"))
        assertNull(PatternStore.bankLabel("com.tidak.ada"))
    }

    @Test
    fun `teks panjang dipotong sebelum dicocokkan pola`() {
        // Penanda rekening yang berada di luar 2000 karakter pertama tidak boleh
        // terbaca. Inilah satu-satunya penahan yang berlaku untuk semua pola,
        // termasuk pola dari server yang lolos saringan ReDoS.
        assertNull(Parser.extractSource("x".repeat(5000) + " dari rekening 8533"))
        assertEquals("8533", Parser.extractSource("dari rekening 8533 " + "x".repeat(500)))
    }
}

/**
 * [Pairer] - dua notifikasi yang satu peristiwa jadi satu `/pindahin`.
 *
 * Fixture-nya transkrip notifikasi sungguhan: transfer BCA -> BRI lewat Flip,
 * yang memunculkan "You spent IDR 3.500.351" di myBCA dan "Dana Rp3.500.000
 * masuk ke rekening ...8533" di BRImo. Selisih 351 adalah fee admin.
 */
class PairerTest {

    private val cfg = PairConfig(enabled = true, windowSec = 300, feeMax = 1000)
    private val t0 = 1_757_000_000_000L

    /** myBCA, sisi keluar. Rekening tujuan tidak disebut sama sekali. */
    private val bcaKeluar = "/tabout bca 3500351"
    /** BRImo, sisi masuk. Rekening asal tidak disebut sama sekali. */
    private val briMasuk = "/tabin bri.ibu 3500000"

    private fun feed(cmd: String, at: Long, buffer: MutableList<Pairer.Pending>) =
        Pairer.feed(cmd, "dk-$cmd", at, cfg, buffer)

    @Test
    fun `transfer antar bank jadi satu pindahin`() {
        val buffer = mutableListOf<Pairer.Pending>()
        val first = feed(bcaKeluar, t0, buffer)
        assertTrue("sisi keluar harus ditahan dulu", first is Pairer.Outcome.Held)

        val second = feed(briMasuk, t0 + 4_000, buffer)
        assertTrue("sisi masuk harus menutup pasangan", second is Pairer.Outcome.Paired)
        val paired = second as Pairer.Outcome.Paired
        // Nominal yang dikirim adalah sisi KELUAR, karena itu yang benar-benar
        // didebet BCA. Sisi masuk ditebak webhook lewat pembulatan ke ribuan.
        assertEquals("/pindahin bca bri.ibu 3500351", paired.command)
        assertEquals("bca", paired.out.accountId)
        assertEquals("bri.ibu", paired.inSide.accountId)
        assertTrue("buffer harus kosong setelah berpasangan", buffer.isEmpty())
    }

    @Test
    fun `urutan terbalik tetap berpasangan`() {
        // Notifikasi bisa tiba dalam urutan mana saja; hasilnya harus sama.
        val buffer = mutableListOf<Pairer.Pending>()
        assertTrue(feed(briMasuk, t0, buffer) is Pairer.Outcome.Held)
        val second = feed(bcaKeluar, t0 + 2_000, buffer)
        assertEquals("/pindahin bca bri.ibu 3500351", (second as Pairer.Outcome.Paired).command)
    }

    @Test
    fun `dana masuk dari luar ditahan lalu dikirim apa adanya`() {
        // Tidak ada sisi keluar yang akan datang, jadi tidak boleh hilang.
        val buffer = mutableListOf<Pairer.Pending>()
        val held = feed("/tabin bri.ibu 750000", t0, buffer)
        assertTrue(held is Pairer.Outcome.Held)
        assertEquals(1, buffer.size)

        assertTrue("belum expired", Pairer.flushExpired(buffer, t0 + 10_000, cfg).isEmpty())
        val flushed = Pairer.flushExpired(buffer, t0 + cfg.windowMs + 1, cfg)
        assertEquals(1, flushed.size)
        assertEquals("/tabin bri.ibu 750000", flushed[0].command)
    }

    @Test
    fun `transfer antar rekening di bank sama tidak berpasangan`() {
        // Tanpa syarat "bank beda", transfer internal ikut terpair dan
        // nominalnya jadi tidak bulat - kerusakan yang tercatat di log 5O.
        val buffer = mutableListOf<Pairer.Pending>()
        assertTrue(feed("/tabout bri.vidha 100000", t0, buffer) is Pairer.Outcome.Held)
        val second = feed("/tabin bri.ibu 100000", t0 + 1_000, buffer)
        assertTrue(
            "bank sama harus ditolak",
            second is Pairer.Outcome.Held
        )
        assertEquals("/tabin bri.ibu 100000", (second as Pairer.Outcome.Held).pending.command)
    }

    @Test
    fun `selisih terlalu besar tidak dianggap fee`() {
        val buffer = mutableListOf<Pairer.Pending>()
        feed("/tabout bca 3500000", t0, buffer)
        val second = feed("/tabin bri.ibu 3000000", t0 + 1_000, buffer)
        assertTrue("beda 500.000 bukan fee", second is Pairer.Outcome.Held)
    }

    @Test
    fun `sisi masuk lebih besar dari sisi keluar ditolak`() {
        val buffer = mutableListOf<Pairer.Pending>()
        feed("/tabout bca 3500000", t0, buffer)
        val second = feed("/tabin bri.ibu 3500351", t0 + 1_000, buffer)
        assertTrue("in > out tidak mungkin terjadi", second is Pairer.Outcome.Held)
    }

    @Test
    fun `di luar jendela waktu tidak berpasangan`() {
        val buffer = mutableListOf<Pairer.Pending>()
        feed("/tabout bca 3500351", t0, buffer)
        val second = feed("/tabin bri.ibu 3500000", t0 + cfg.windowMs + 1, buffer)
        assertTrue("jarak waktu lewat jendela", second is Pairer.Outcome.Held)
    }

    @Test
    fun `pindahin yang sudah utuh tidak ikut ditahan`() {
        val buffer = mutableListOf<Pairer.Pending>()
        val out = Pairer.feed("/pindahin bri.ibu bri.vidha 100000", "dk", t0, cfg, buffer)
        assertTrue(out is Pairer.Outcome.Pass)
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `pairs mati berarti tidak ada yang ditahan`() {
        val off = PairConfig(enabled = false)
        val buffer = mutableListOf<Pairer.Pending>()
        val out = Pairer.feed(bcaKeluar, "dk", t0, off, buffer)
        assertTrue(out is Pairer.Outcome.Pass)
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `satu sisi tidak dipakai dua kali`() {
        // Dua dana masuk berturut-turut, satu dana keluar: hanya yang pertama
        // boleh berpasangan. Kalau tidak, satu sisi jadi dua /pindahin.
        val buffer = mutableListOf<Pairer.Pending>()
        feed("/tabout bca 3500351", t0, buffer)
        assertTrue(feed("/tabin bri.ibu 3500000", t0 + 1_000, buffer) is Pairer.Outcome.Paired)
        val third = feed("/tabin bri.ibu 3500000", t0 + 2_000, buffer)
        assertTrue("sudah berpasangan, sisanya ditahan", third is Pairer.Outcome.Held)
    }

    // ---- Parameter data: nilai di luar batas ditolak, bukan dipercaya ----

    @Test
    fun `window dan fee di luar batas memakai nilai default`() {
        val nakal = PairConfig.fromJson(
            org.json.JSONObject("""{"enabled":true,"windowSec":999999,"feeMax":-5}""")
        )
        assertEquals(300, nakal.windowSec)
        assertEquals(1000, nakal.feeMax)
    }

    @Test
    fun `pairs tanpa blok akan mati`() {
        // APK lama, atau rules.json yang belum punya blok ini: harus mati,
        // bukan memakai angka yang tidak disepakati.
        assertFalse(PairConfig.fromJson(null).enabled)
    }

    @Test
    fun `pairs tidak hilang saat json dibolak balik lewat editor`() {
        // Dialog Aturan menyalin JSON lewat RuleSet.toJson. Kalau `pairs` tidak
        // ikut ditulis, parameter ini hilang diam-diam setiap kali aturan diedit.
        val asli = RuleSet.fromJson("""{"version":1,"accounts":{},"rules":[],
            "pairs":{"enabled":true,"windowSec":45,"feeMax":777}}""")!!
        val pulih = RuleSet.fromJson(RuleSet.toJson(asli))!!
        assertTrue(pulih.pairs.enabled)
        assertEquals(45, pulih.pairs.windowSec)
        assertEquals(777, pulih.pairs.feeMax)
    }

    @Test
    fun `rules json bawaan punya blok pairs yang sengaja mati`() {
        val f = File("src/main/assets/rules.json")
        assertTrue("assets/rules.json tidak ditemukan", f.exists())
        val set = RuleSet.fromJson(f.readText())
            ?: throw AssertionError("assets/rules.json tidak bisa di-parse")
        // Pairing DIMATIKAN dengan sengaja (rules.json v5, 2026-09-30).
        // Pairer menahan setiap tabin/tabout, padahal hanya transfer antar bank
        // yang punya dua sisi notifikasi - jadi transfer masuk dari merchant
        // (Gopay) ikut tertahan 28 menit sambil menunggu pasangan yang tidak
        // akan pernah datang. Blok ini sengaja dipertahankan supaya Pairer bisa
        // dihidupkan lagi dari server tanpa build, tapi TIDAK dipakai dulu.
        //
        // Kalau ini diaktifkan, test ini harus ikut diubah - dan alasan
        // mengaktifkannya harus ditulis di sini, bukan dihapus assertion-nya.
        assertFalse("pairing harus mati; lihat catatan di atas", set.pairs.enabled)
        assertEquals(300, set.pairs.windowSec)
        assertEquals(1000, set.pairs.feeMax)
    }

    // ---- Bentuk kunci yang dipakai server ----

    @Test
    fun `kunci pasangan sesuai bentuk yang diterima server`() {
        // `telegram.php` menolak kunci yang tidak berbentuk
        // `ibanknotif:v1:<64 hex>`, lalu menjawab "deduplicated" dan KELUAR tanpa
        // mencatat apa pun. Bentuk yang salah berarti transaksi hilang diam-diam,
        // jadi bentuknya dikunci di sini.
        val pola = Regex("""^ibanknotif:v1:[a-f0-9]{64}$""")
        val ko = Sink.dedupKey("bca", "com.bca.mybca", 1757000000L, "You spent IDR 3,500,351.00")
        val ki = Sink.dedupKey("bri", "id.co.bri.brimo", 1757000004L, "Dana Rp3.500.000 masuk")
        val kp = Sink.pairKey(ko, ki)
        assertNotNull("kunci pasangan tidak boleh null", kp)
        assertTrue("kunci pasangan ditolak server: $kp", pola.matches(kp!!))
        assertNotEquals("kunci pasangan harus beda dari kedua sisi", ko, kp)
        assertNotEquals(ki, kp)
    }

    @Test
    fun `kunci pasangan kosong kalau salah satu sisi tidak punya kunci`() {
        val ko = Sink.dedupKey("bca", "com.bca.mybca", 1757000000L, "x")
        assertNull(Sink.pairKey(ko, null))
        assertNull(Sink.pairKey(null, ko))
    }
}

/**
 * Model AI. `gemini-2.0-flash` dicabut Google dan Pindai AI mati dengan 404
 * padahal tidak ada baris kode yang salah - jadi model harus data, bukan
 * konstanta. Yang diuji: default hidup, nilai server dipakai, bentuk salah
 * ditolak, dan tidak hilang saat JSON dibolak balik lewat editor.
 */
class AiConfigTest {

    @Test
    fun `tanpa blok memakai default yang tidak sudah mati`() {
        assertEquals(AiConfig.DEFAULT_MODEL, AiConfig.fromJson(null).model)
    }

    @Test
    fun `model dari server dipakai apa adanya`() {
        val a = AiConfig.fromJson(
            org.json.JSONObject("""{"model":"gemini-2.5-flash"}""")
        )
        assertEquals("gemini-2.5-flash", a.model)
    }

    @Test
    fun `bentuk model yang salah ditolak dan defaultnya dipakai`() {
        // Nilainya jadi potongan path URL yang sudah membawa API key, jadi
        // `../` dan `?key=` harus jatuh ke default, bukan dipakai.
        for (nakal in listOf("../v1/models/x", "gemini?key=abc", "gemini/flash",
                            "GEMINI-FLASH", "", "   ", "x".repeat(65))) {
            val a = AiConfig.fromJson(
                org.json.JSONObject("""{"model":${org.json.JSONObject.quote(nakal)}}""")
            )
            assertEquals("model nakal harus ditolak: '$nakal'",
                AiConfig.DEFAULT_MODEL, a.model)
        }
    }

    @Test
    fun `model yang bukan teks ditolak dan defaultnya dipakai`() {
        // optString() meng-coerce: JSON null jadi "null" dan angka jadi "123".
        // "null" justru LOLOS regex, lalu URL jadi ".../models/null:generateContent".
        // Jadi nilai non-String harus ditolak sebelum diuji bentuknya.
        assertEquals(AiConfig.DEFAULT_MODEL, AiConfig.fromJson(
            org.json.JSONObject("""{"model":null}""")).model)
        assertEquals(AiConfig.DEFAULT_MODEL, AiConfig.fromJson(
            org.json.JSONObject("""{"model":123}""")).model)
        assertEquals(AiConfig.DEFAULT_MODEL, AiConfig.fromJson(
            org.json.JSONObject("""{"model":true}""")).model)
    }

    @Test
    fun `ai tidak hilang saat json dibolak balik lewat editor`() {
        // Dialog Aturan menyalin JSON lewat RuleSet.toJson. Kalau `ai` tidak ikut
        // ditulis, model kembali ke default setiap kali pengguna mengedit -
        // persis kelas bug yang membuat data lain hilang diam-diam.
        val asli = RuleSet.fromJson("""{"version":1,"accounts":{},"rules":[],
            "ai":{"model":"gemini-2.5-flash"}}""")!!
        val pulih = RuleSet.fromJson(RuleSet.toJson(asli))!!
        assertEquals("gemini-2.5-flash", pulih.ai.model)
    }

    @Test
    fun `rules json tanpa blok ai tetap valid untuk apk lama`() {
        // APK lama mengabaikan kunci asing lewat optJSONObject, jadi file server
        // yang sudah punya `ai` tidak boleh membuat APK lama menolak semuanya.
        val set = RuleSet.fromJson(
            """{"version":3,"accounts":{"8533":"bri.ibu"},"rules":[]}"""
        ) ?: throw AssertionError("gagal parse")
        assertEquals(AiConfig.DEFAULT_MODEL, set.ai.model)
    }

    @Test
    fun `rules json bawaan memakai model yang hidup`() {
        val f = File("src/main/assets/rules.json")
        assertTrue("assets/rules.json tidak ditemukan", f.exists())
        val set = RuleSet.fromJson(f.readText())
            ?: throw AssertionError("assets/rules.json tidak bisa di-parse")
        assertEquals("gemini-flash-latest", set.ai.model)
    }
}

/**
 * Setelan pengiriman dan housekeeping: nilai dari server, defaultnya sama
 * dengan perilaku lama, dan angka di luar batas ditolak.
 */
class DeliveryConfigTest {

    @Test
    fun `tanpa blok memakai default yang sama dengan sebelumnya`() {
        val d = DeliveryConfig.fromJson(null)
        assertEquals(3, d.maxRetries)
        assertEquals(1500, d.retryDelayMs)
        assertEquals(10000, d.timeoutMs)
        assertEquals(500, d.sendGapMs)
        val r = RuntimeConfig.fromJson(null)
        assertEquals(100, r.logLines)
        assertEquals(200, r.maxProcessedKeys)
    }

    @Test
    fun `nilai dari server dipakai apa adanya`() {
        val d = DeliveryConfig.fromJson(
            org.json.JSONObject(
                """{"maxRetries":5,"retryDelayMs":800,"timeoutMs":3000,"sendGapMs":200}"""
            )
        )
        assertEquals(5, d.maxRetries)
        assertEquals(800, d.retryDelayMs)
        assertEquals(3000, d.timeoutMs)
        assertEquals(200, d.sendGapMs)
    }

    @Test
    fun `angka mustahil ditolak dan defaultnya dipakai`() {
        // Retry tanpa batas membuat aplikasi tidak bisa dihentikan, dan angka
        // absurd di file server hampir selalu salah kirim.
        val nakal = DeliveryConfig.fromJson(
            org.json.JSONObject("""{"maxRetries":99999,"retryDelayMs":-1,"timeoutMs":0}""")
        )
        assertEquals(3, nakal.maxRetries)
        assertEquals(1500, nakal.retryDelayMs)
        assertEquals(10000, nakal.timeoutMs)
    }

    @Test
    fun `runtime terlalu kecil ditolak`() {
        // Batas bawah 10: log 1 baris tidak berguna dan hanya membuang Context.
        val kecil = RuntimeConfig.fromJson(
            org.json.JSONObject("""{"logLines":1,"maxProcessedKeys":0}""")
        )
        assertEquals(100, kecil.logLines)
        assertEquals(200, kecil.maxProcessedKeys)
    }

    @Test
    fun `delivery dan runtime tidak hilang saat json dibolak balik`() {
        // Dialog Aturan menyalin JSON lewat RuleSet.toJson. Field yang tidak
        // dimodelkan akan hilang diam-diam setiap kali pengguna mengedit.
        val asli = RuleSet.fromJson(
            """{"version":1,"accounts":{},"rules":[],
               "delivery":{"maxRetries":7,"sendGapMs":111},
               "runtime":{"logLines":222,"maxProcessedKeys":333}}"""
        )!!
        val pulih = RuleSet.fromJson(RuleSet.toJson(asli))!!
        assertEquals(7, pulih.delivery.maxRetries)
        assertEquals(111, pulih.delivery.sendGapMs)
        assertEquals(222, pulih.runtime.logLines)
        assertEquals(333, pulih.runtime.maxProcessedKeys)
    }

    @Test
    fun `rules json bawaan punya blok delivery dan runtime`() {
        val f = File("src/main/assets/rules.json")
        assertTrue("assets/rules.json tidak ditemukan", f.exists())
        val set = RuleSet.fromJson(f.readText())
            ?: throw AssertionError("assets/rules.json tidak bisa di-parse")
        assertEquals(3, set.delivery.maxRetries)
        assertEquals(100, set.runtime.logLines)
    }

    @Test
    fun `rules json tanpa blok delivery tetap valid untuk apk lama`() {
        // fromJson versi lama hanya membaca version/accounts/knownIds/rules.
        // Kunci asing harus diabaikan, bukan membuat file ditolak.
        val set = RuleSet.fromJson(
            """{"version":3,"accounts":{"8533":"bri.ibu"},"rules":[]}"""
        ) ?: throw AssertionError("gagal parse")
        assertEquals(3, set.delivery.maxRetries)
        assertEquals(100, set.runtime.logLines)
    }
}
