package com.websolusi.ibanknotif

import android.content.Context
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object Sink {

    private var updateId = System.currentTimeMillis().toInt()

    fun send(ctx: Context, cmd: String) {
        Thread {
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
        }.start()
    }
}
