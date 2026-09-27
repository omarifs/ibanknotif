package com.websolusi.ibanknotif

import org.junit.Assert.assertEquals
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
    fun `pindahin dari 8533 ke 3508`() {
        assertEquals("8533", Parser.extractSource(PINDAHIN_8533_3508))
        assertEquals("3508", Parser.extractDestination(PINDAHIN_8533_3508))
    }

    @Test
    fun `pindahin dari 3508 ke 8533`() {
        assertEquals("3508", Parser.extractSource(PINDAHIN_3508_8533))
        assertEquals("8533", Parser.extractDestination(PINDAHIN_3508_8533))
    }

    @Test
    fun `rekening ber-strip dan ber-spasi tetap terbaca`() {
        val t = "05/09/2026 09:00:00 - Transfer dari XXXXX-XXXX-8533 dengan nomor rekening tujuan XXXXX-XXXX-3508 sebesar Rp250.000,00 BERHASIL."
        assertEquals("8533", Parser.extractSource(t))
        assertEquals("3508", Parser.extractDestination(t))
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
    fun `dua notif satu transfer menghasilkan tanda tangan sama`() {
        val detail = Parser.transactionSignature("bri", PINDAHIN_8533_3508, 100000)
        val ringkas = Parser.transactionSignature("bri", SOBAT_100000, 100000)
        assertNotNull(detail)
        assertNotNull(ringkas)
        assertEquals(detail, ringkas)
    }

    @Test
    fun `notif tanpa waktu event tidak punya tanda tangan`() {
        assertNull(Parser.transactionSignature("bca", "You spent IDR 7,675.00 at Groceries.", 7675))
    }

    @Test
    fun `nominal berbeda menghasilkan tanda tangan berbeda`() {
        val a = Parser.transactionSignature("bri", PINDAHIN_8533_3508, 100000)
        val b = Parser.transactionSignature("bri", PINDAHIN_8533_3508, 200000)
        assertTrue(a != b)
    }

    companion object {
        const val PINDAHIN_8533_3508 = "03/09/2026 07:12:49 - Transfer dari XXXXXXXXXX8533 dengan nomor rekening tujuan XXXXXXXXXX3508 sebesar Rp100.000,00 BERHASIL. Info lebih lanjut hubungi Call Center BRI 1500017"
        const val PINDAHIN_3508_8533 = "03/09/2026 07:12:49 - Transfer dari XXXXXXXXXX3508 dengan nomor rekening tujuan XXXXXXXXXX8533 sebesar Rp100.000,00 BERHASIL."
        const val SOBAT_10000 = "Sobat BRI! Dana Rp10.000 masuk ke rekening 000000000008533 pada 31/08/2026 17:11:17 KET:NBMB MUFIDATUL UMMA TO NURHASANAH"
        const val SOBAT_100000 = "Sobat BRI! Dana Rp100.000 masuk ke rekening 000000000008533 pada 31/08/2026 17:11:17 KET:X"
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
    fun `pindahin dari 8533 ke 3508`() {
        assertEquals("/pindahin bri.ibu bri.vidha 100000", run("bri", ParserTest.PINDAHIN_8533_3508))
    }

    @Test
    fun `pindahin dari 3508 ke 8533`() {
        assertEquals("/pindahin bri.vidha bri.ibu 100000", run("bri", ParserTest.PINDAHIN_3508_8533))
    }

    @Test
    fun `pindahin dengan rekening ber-strip`() {
        val t = "05/09/2026 09:00:00 - Transfer dari XXXXX-XXXX-8533 dengan nomor rekening tujuan XXXXX-XXXX-3508 sebesar Rp250.000,00 BERHASIL."
        assertEquals("/pindahin bri.ibu bri.vidha 250000", run("bri", t))
    }

    @Test
    fun `transfer ke rekening sendiri tidak dicatat`() {
        val t = "06/09/2026 10:00:00 - Transfer dari XXXXXXXXXX8533 dengan nomor rekening tujuan XXXXXXXXXX8533 sebesar Rp50.000,00 BERHASIL."
        assertNull(run("bri", t))
    }

    @Test
    fun `transfer masuk dari rekening luar bukan pindahin`() {
        val t = "31/08/2026 17:11:17 - Transfer dari XXXXXXXXXX5530 dengan nomor rekening tujuan XXXXXXXXXX8533 sebesar Rp10.000,00 BERHASIL."
        assertEquals("/tabin bri.ibu 10000", run("bri", t))
    }

    @Test
    fun `transfer keluar ke rekening luar bukan pindahin`() {
        val t = "31/08/2026 17:11:17 - Transfer dari XXXXXXXXXX3508 dengan nomor rekening tujuan XXXXXXXXXX9911 sebesar Rp75.000,00 BERHASIL."
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
    fun `top up shopeepay`() {
        val t = "02/09/2026 12:10:19 - Transaksi Top Up ShopeePay 083124253640 sebesar Rp200.000,00 BERHASIL. Info lebih lanjut hubungi Call Center BRI 1500017"
        assertEquals("/tabout bri.ibu 200000", run("bri", t))
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
    fun `notif pasangan transfer tetap boleh dicatat sebagai keluar`() {
        val t = "03/09/2026 07:12:49 - Dana Rp100.000 keluar dari rekening 4737010273673508"
        assertEquals("/tabout bri.vidha 100000", run("bri", t))
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
    ) = Rule(id, "uji", "bri", priority, true, when_, command, stop)

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
        val parsed = Parser.parseByBank("bri", ParserTest.PINDAHIN_8533_3508)!!
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
