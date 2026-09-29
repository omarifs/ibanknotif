package com.websolusi.ibanknotif

/**
 * Menyatukan dua notifikasi yang sebenarnya satu peristiwa menjadi satu
 * `/pindahin`.
 *
 * **Masalah yang diselesaikan.** Transfer antar bank lewat Flip memunculkan dua
 * notifikasi dari dua aplikasi berbeda: sisi keluar ("You spent IDR 3.500.351 at
 * Account Transfer") dan sisi masuk ("Dana Rp3.500.000 masuk ke rekening ...").
 * Tidak ada satu notifikasi pun yang memuat kedua rekening, jadi satu aturan
 * tidak bisa menghasilkannya - kosakata `when` yang tertutup tidak punya token
 * untuk menggabungkan dua notifikasi.
 *
 * Yang bisa dilakukan: **menahan** hasil klasifikasi sebentar sambil menunggu
 * sisi pasangannya. Kalau pasangannya datang, dua baris berubah jadi satu
 * `/pindahin`. Kalau tidak, sisi yang tertahan dikirim apa adanya setelah
 * [PairConfig.windowMs] - jadi perilaku lamanya tetap terjadi, hanya telat.
 *
 * **Syarat pemasangan.** Semuanya harus benar. Salah satu saja tidak terpenuhi
 * berarti tidak ada pasangan:
 *
 * 1. Arah berlawanan (satu `in`, satu `out`)
 * 2. Kedua id adalah rekening sendiri - sudah dijamin [RuleEngine.isSendable]
 * 3. Prefix bank berbeda (`bri` vs `bca` vs `mandiri`)
 * 4. `in <= out`: fee menambah sisi keluar, tidak pernah sebaliknya
 * 5. `out - in <= feeMax`: selisih dibaca sebagai fee admin
 * 6. Jarak waktu tidak melewati [PairConfig.windowMs]
 *
 * Syarat 3-6 inilah yang mencegah salah pasang. Tanpa syarat 3, transfer antar
 * rekening di bank yang sama ikut terpair dan nominalnya jadi tidak bulat
 * - persis kerusakan yang tercatat di `LOG-PROYEK.md` bagian 5O.
 *
 * **Batas yang tidak bisa dilawan.** Yang dipairkan hanya dua notifikasi yang
 * tiba di HP yang sama. Transfer dari HP satu ke rekening di HP lain tidak
 * pernah bertemu di sini: tiap HP punya buffer sendiri dan tidak ada kanal
 * antar-HP. Saldo di kedua sisi tetap benar karena masing-masing sisi mengirim
 * `/tabout` dan `/tabin` seperti biasa - yang hilang hanya penanda bahwa keduanya
 * satu peristiwa.
 *
 * Tidak ada Android di file ini. Buffer disimpan di memori; persistensinya
 * ada di [Config].
 */
object Pairer {

    /** Arah transaksi, diturunkan dari bentuk command-nya. */
    enum class Direction { IN, OUT, OTHER }

    /**
     * Satu sisi yang sedang ditahan. [atMs] adalah jam perangkat notifikasi itu
     * sampai, bukan jam server, karena yang dibandingkan adalah dua notifikasi
     * di satu perangkat.
     */
    data class Pending(
        val command: String,
        val accountId: String,
        val amount: Long,
        val direction: Direction,
        val atMs: Long,
        /** Kunci anti-duplikat notifikasi ini, untuk di-mark saat akhirnya dikirim. */
        val dedupKey: String?
    )

    /** Hasil satu panggilan [feed]. */
    sealed class Outcome {
        /** Dua sisi jadi satu. [out] jadi asal, [inSide] jadi tujuan. */
        data class Paired(val command: String, val out: Pending, val inSide: Pending) : Outcome()

        /** Belum ada pasangan. Sisi ini ditahan. */
        data class Held(val pending: Pending) : Outcome()

        /** Bukan kandidat pasangan (mis. `/pindahin` utuh). Langsung kirim. */
        data class Pass(val command: String, val dedupKey: String?) : Outcome()
    }

    /**
     * Sisi yang layak ditahan: `/tabin` atau `/tabout` dengan satu id rekening
     * dan nominal bulat. `/pindahin` sudah utuh satu peristiwa dan tidak pernah
     * ikut ditahan.
     */
    private val SINGLE = Regex("""^/(tabin|tabout) ([a-z0-9._]{1,40}) (\d{1,14})$""")

    private fun directionOf(command: String): Direction = when {
        command.startsWith("/tabin ") -> Direction.IN
        command.startsWith("/tabout ") -> Direction.OUT
        else -> Direction.OTHER
    }

    /** Prefix bank dari id akun: `bri.ibu` -> `bri`. */
    fun bankOf(accountId: String): String = accountId.substringBefore('.').take(3)

    /**
     * Pecah command satu-sisi jadi [Pending], atau null kalau bukan kandidat.
     * Bentuknya sudah dikunci oleh gerbang [RuleEngine.isSendable], jadi ini
     * hanya baca ulang regex yang sama - bukan parsing bebas.
     */
    fun parse(command: String, dedupKey: String?, atMs: Long): Pending? {
        val m = SINGLE.matchEntire(command) ?: return null
        val amount = m.groupValues[3].toLongOrNull() ?: return null
        return Pending(
            command = command,
            accountId = m.groupValues[2],
            amount = amount,
            direction = directionOf(command),
            atMs = atMs,
            dedupKey = dedupKey
        )
    }

    /**
     * Apakah dua sisi boleh disatukan. Mengembalikan alasan kalau tidak, supaya
     * penolakan bisa dibedakan dari "tidak ada yang mencoba" saat menelusuri log.
     */
    fun canPairWith(a: Pending, b: Pending, cfg: PairConfig): String? {
        if (a.direction == b.direction) return "arah sama"
        val (out, inSide) = if (a.direction == Direction.OUT) a to b else b to a
        if (bankOf(out.accountId) == bankOf(inSide.accountId)) return "bank sama"
        if (inSide.amount > out.amount) return "sisi masuk lebih besar dari sisi keluar"
        if (out.amount - inSide.amount > cfg.feeMax) return "selisih melebihi batas fee"
        val gap = kotlin.math.abs(out.atMs - inSide.atMs)
        if (gap > cfg.windowMs) return "jarak waktu melebihi jendela"
        return null
    }

    /** Susun command `/pindahin` dari dua sisi yang sudah lolos [canPairWith]. */
    fun render(out: Pending, inSide: Pending): String =
        "/pindahin ${out.accountId} ${inSide.accountId} ${out.amount}"

    /**
     * Masukkan satu hasil klasifikasi. [Outcome] yang dihasilkan yang menentukan
     * apa yang dikirim: [Outcome.Pass] langsung kirim, [Outcome.Held] ditahan,
     * [Outcome.Paired] berarti dua sisi disatukan jadi satu command.
     *
     * [buffer] adalah daftar sisi yang sedang ditahan. Yang cocok dengan sisi
     * baru dikeluarkan dari sana dan keduanya disatukan.
     */
    fun feed(
        command: String,
        dedupKey: String?,
        atMs: Long,
        cfg: PairConfig,
        buffer: MutableList<Pending>
    ): Outcome {
        val p = parse(command, dedupKey, atMs)
            ?: return Outcome.Pass(command, dedupKey)
        if (!cfg.enabled) return Outcome.Pass(command, dedupKey)

        val idx = buffer.indexOfFirst { canPairWith(it, p, cfg) == null }
        if (idx < 0) {
            buffer.add(p)
            return Outcome.Held(p)
        }
        val other = buffer.removeAt(idx)
        val out = if (p.direction == Direction.OUT) p else other
        val inSide = if (p.direction == Direction.OUT) other else p
        return Outcome.Paired(render(out, inSide), out, inSide)
    }

    /**
     * Sisi yang sudah lewat jendela dan tidak pernah berpasangan. Harus dikirim
     * apa adanya - inilah yang membuat kegagalan pairing hanya menunda, tidak
     * menghilangkan notifikasi bank.
     *
     * Mengembalikan [Pending] asli supaya pemanggil bisa menghapus persis
     * objek yang dikirim, bukan mencocokkan ulang dengan teks command.
     */
    fun flushExpired(buffer: List<Pending>, nowMs: Long, cfg: PairConfig): List<Pending> =
        buffer.filter { nowMs - it.atMs > cfg.windowMs }
}
