package com.bitchat.android.ui.pay

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Writes this phone's tag and smart-account address into the Sozu directory. */
object SozuDirectory {
    private const val ENDPOINT = "https://app.sozu.capital/api/wallet/sozu-tag"

    fun publish(tag: String, stellarAddress: String): Boolean {
        if (!isValidSozuTag(tag) || !isValidStellarAddress(stellarAddress)) return false
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val body = JSONObject()
                .put("tag", tag)
                .put("stellarAddress", stellarAddress)
                .toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}
