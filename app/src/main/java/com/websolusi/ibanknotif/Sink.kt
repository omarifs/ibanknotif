package com.websolusi.ibanknotif

import android.content.Context
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.LinkedList

object Sink {

    private var updateId = System.currentTimeMillis().toInt()

    // Antrean FIFO: 1 worker kirim berurutan (notif 1 selalu sebelum notif 2),
    // jeda 500ms antar POST utk menghindari input barengan ke server.
    private val queue = LinkedList<Quad>()
    @Volatile private var worker: Thread? = null

    /** Satu pekerjaan di antrean, beserta setelan pengiriman saat antrean dibuat. */
    private class Quad(
        val ctx: Context,
        val cmd: String,
        val dedupKey: String?,
        val cfg: DeliveryConfig
    )

    /**
     * Setelan pengiriman aktif. Dibaca sekali saat command masuk antrean,
     * bukan tiap percobaan, supaya satu pengiriman tidak berubah di tengah jalan.
     */
    private fun deliveryOf(ctx: Context): DeliveryConfig = try {
        RuleStore.load(ctx).delivery
    } catch (_: Exception) {
        DeliveryConfig()
    }

    fun dedupKey(bank: String, packageName: String, postTime: Long, text: String): String {
        val input = "v1|$bank|$packageName|$postTime|$text"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return "ibanknotif:v1:" + digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Kunci untuk satu `/pindahin` yang disusun [Pairer] dari DUA notifikasi.
     *
     * Bentuknya tetap `ibanknotif:v1:` + 64 hex, jadi lolos penyaring
     * `claim_dedup_key` di `telegram.php`. Kalau tidak, webhook menjawab
     * `deduplicated` dan keluar tanpa mencatat apa pun - jadi kunci yang salah
     * bentuk berarti transaksi hilang diam-diam.
     *
     * Isinya dua kunci sisi digabung, bukan satu: memakai kunci milik salah satu
     * sisi akan membuat notifikasi sah yang kebetulan memakai kunci itu
     * dianggap duplikat oleh server.
     */
    fun pairKey(keyOut: String?, keyIn: String?): String? {
        if (keyOut == null || keyIn == null) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("pair1|$keyOut|$keyIn".toByteArray())
        return "ibanknotif:v1:" + digest.joinToString("") { "%02x".format(it) }
    }

    fun send(ctx: Context, cmd: String, dedupKey: String? = null) {
        synchronized(queue) {
            queue.add(Quad(ctx, cmd, dedupKey, deliveryOf(ctx)))
            if (worker == null || !worker!!.isAlive) {
                worker = Thread(startWorker()).apply { start() }
            }
        }
    }

    /**
     * Antrean ini in-memory, jadi kalau proses mati, apa pun yang belum terkirim
     * hilang. Itu sebabnya [NotifService] menulis command ke outbox disk lebih
     * dulu sebelum memanggil [send].
     */
    private fun startWorker() = Runnable {
        while (true) {
            val job = synchronized(queue) {
                if (queue.isEmpty()) null else queue.removeFirst()
            }
            if (job == null) return@Runnable
            post(job.ctx, job.cmd, job.dedupKey, job.cfg)
            try {
                Thread.sleep(job.cfg.sendGapMs.toLong())
            } catch (_: InterruptedException) {
                return@Runnable
            }
        }
    }

    /**
     * Kirim satu command, ulang sampai [DeliveryConfig.maxRetries] kali.
     *
     * Retry itu penting karena jaringan HP sering putus sesaat, dan antrean ini
     * tidak punya tempat lain: kalau percobaan terakhir gagal, command itu
     * kembali ke outbox dan dicoba lagi saat aplikasi dibuka.
     *
     * Respons HTTP 200 tidak selalu berarti berhasil. `telegram.php` menjawab
     * `deduplicated: true` lalu keluar tanpa menyimpan apa pun kalau kunci
     * dedup sudah pernah diklaim - jadi body harus dibaca, bukan cuma
     * `responseCode`, kalau tidak kehilangan transaksi terlihat seperti sukses.
     */
    private fun post(ctx: Context, cmd: String, dedupKey: String?, cfg: DeliveryConfig) {
        var percobaan = 0
        while (percobaan < cfg.maxRetries) {
            percobaan++
            val hasil = sekali(ctx, cmd, dedupKey, cfg)
            if (hasil == Hasil.BERHASIL) {
                Config.appendLog(
                    ctx,
                    if (percobaan > 1) "POST $cmd → 200 (percobaan $percobaan)"
                    else "POST $cmd → 200"
                )
                return
            }
            if (hasil == Hasil.DITOLAK_DEDUP) {
                // Server menolak, jadi mengulang tidak akan menolong. Dicatat
                // apa adanya supaya tidak hilang tanpa jejak.
                Config.appendLog(ctx, "DITOLAK-DEDUP (tidak masuk DB): $cmd")
                return
            }
            Config.appendLog(ctx, "GAGAL kirim (percobaan $percobaan): $cmd")
            if (percobaan < cfg.maxRetries) {
                try {
                    Thread.sleep(cfg.retryDelayMs.toLong())
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
        Config.appendLog(ctx, "GAGAL total setelah ${cfg.maxRetries}x: $cmd")
    }

    private enum class Hasil { BERHASIL, DITOLAK_DEDUP, GAGAL }

    /** Satu percobaan. Tidak melempar exception. */
    private fun sekali(
        ctx: Context,
        cmd: String,
        dedupKey: String?,
        cfg: DeliveryConfig
    ): Hasil {
        var conn: HttpURLConnection? = null
        return try {
            val body = JSONObject().apply {
                put("update_id", updateId++)
                put("message", JSONObject().apply {
                    put("message_id", 1)
                    put("date", System.currentTimeMillis() / 1000)
                    put("chat", JSONObject().apply { put("id", Config.chatId(ctx)) })
                    put("text", cmd)
                    if (!dedupKey.isNullOrBlank()) put("dedup_key", dedupKey)
                })
            }
            conn = (URL(Config.endpoint(ctx)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connectTimeout = cfg.timeoutMs
                readTimeout = cfg.timeoutMs
                doOutput = true
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
            val code = conn.responseCode
            val resp = bacaBody(conn, code)
            if (code in 200..299) {
                if (resp.contains("\"deduplicated\":true")) Hasil.DITOLAK_DEDUP else Hasil.BERHASIL
            } else {
                Hasil.GAGAL
            }
        } catch (_: Exception) {
            // Jangan log pesan exception: HttpURLConnection bisa memuat URL lengkap.
            Hasil.GAGAL
        } finally {
            conn?.disconnect()
        }
    }

    /** Baca body respons. Error di sini tidak boleh menggagalkan pengiriman. */
    private fun bacaBody(conn: HttpURLConnection, code: Int): String = try {
        val s = if (code in 200..299) conn.inputStream else conn.errorStream
        if (s == null) "" else s.bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        ""
    }

}
