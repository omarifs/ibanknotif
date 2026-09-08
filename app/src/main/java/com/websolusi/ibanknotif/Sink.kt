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
    private val queue = LinkedList<Triple<Context, String, String?>>()
    @Volatile private var worker: Thread? = null

    fun dedupKey(bank: String, packageName: String, postTime: Long, text: String): String {
        val input = "v1|$bank|$packageName|$postTime|$text"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return "ibanknotif:v1:" + digest.joinToString("") { "%02x".format(it) }
    }

    fun send(ctx: Context, cmd: String, dedupKey: String? = null) {
        synchronized(queue) {
            queue.add(Triple(ctx, cmd, dedupKey))
            if (worker == null || !worker!!.isAlive) {
                worker = Thread(startWorker()).apply { start() }
            }
        }
    }

    private fun startWorker() = Runnable {
        while (true) {
            val job = synchronized(queue) {
                if (queue.isEmpty()) null else queue.removeFirst()
            }
            if (job == null) return@Runnable
            post(job.first, job.second, job.third)
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return@Runnable
            }
        }
    }

    private fun post(ctx: Context, cmd: String, dedupKey: String?) {
        try {
            val endpoint = Config.endpoint(ctx)
            val chatId = Config.chatId(ctx)

            val body = JSONObject().apply {
                put("update_id", updateId++)
                put("message", JSONObject().apply {
                    put("message_id", 1)
                    put("date", System.currentTimeMillis() / 1000)
                    put("chat", JSONObject().apply {
                        put("id", chatId)
                    })
                    put("text", cmd)
                    if (!dedupKey.isNullOrBlank()) put("dedup_key", dedupKey)
                })
            }

            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream, "UTF-8").use {
                it.write(body.toString())
            }

            val code = conn.responseCode
            Config.appendLog(ctx, "POST $cmd → $code")
            conn.disconnect()
        } catch (e: Exception) {
            Config.appendLog(ctx, "ERR $cmd → ${e.message}")
        }
    }
}