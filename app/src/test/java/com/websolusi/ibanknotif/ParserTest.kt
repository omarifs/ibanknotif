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
         * peta rekening memakai empat. Jadi tidak ada akun yang bisa ditentukan,
         * dan aturannya harus diam, bukan menebak.
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
    fun `notif riwayat BRImo hanya menampakkan tiga digit terakhir tidak dicatat`() {
        assertNull(run("bri", ParserTest.TOPUP_SHOPEEPAY_RIWAYAT))
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
