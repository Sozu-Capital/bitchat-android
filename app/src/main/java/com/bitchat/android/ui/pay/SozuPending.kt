package com.bitchat.android.ui.pay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Cheques signed offline. The first phone with a connection submits them. */
object SozuPending {
    private const val ENDPOINT = "https://app.sozu.capital/api/offline/submit"
    private const val RESOLVE = "https://app.sozu.capital/api/wallet/resolve-recipient"

    fun remember(context: Context, frame: String) {
        val text = frame.trim()
        if (!text.startsWith("SOZU1:")) return
        val prefs = context.getSharedPreferences("sozu-pay", Context.MODE_PRIVATE)
        val current = pending(prefs)
        for (i in 0 until current.length()) {
            if (current.optString(i) == text) return
        }
        current.put(text)
        while (current.length() > 20) current.remove(0)
        prefs.edit().putString("pending", current.toString()).apply()
    }

    fun online(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Returns the tx hash of the last cheque the vault accepted. */
    fun flush(context: Context, settler: String): String? {
        if (!online(context) || !isValidStellarAddress(settler)) return null
        val prefs = context.getSharedPreferences("sozu-pay", Context.MODE_PRIVATE)
        val current = pending(prefs)
        val left = JSONArray()
        var hash: String? = null
        for (i in 0 until current.length()) {
            val frame = current.optString(i)
            val submitted = postFrame(frame, settler)
            if (submitted != null) hash = submitted else left.put(frame)
        }
        prefs.edit().putString("pending", left.toString()).apply()
        return hash
    }

    fun resolveTag(tag: String): String? {
        if (!isValidSozuTag(tag)) return null
        val connection = (URL(RESOLVE).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val body = JSONObject().put("recipient", tag).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.bufferedReader().readText()
            JSONObject(text).optString("walletAddress").trim().uppercase().takeIf(::isValidStellarAddress)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun postFrame(frame: String, settler: String): String? {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 55000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val body = JSONObject().put("frame", frame).put("settler", settler).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.bufferedReader().readText()
            JSONObject(text).optString("hash").takeIf { it.length >= 16 }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun pending(prefs: android.content.SharedPreferences): JSONArray {
        return try {
            JSONArray(prefs.getString("pending", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
    }
}
