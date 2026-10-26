package com.bitchat.android.ui.pay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SozuUpload(val frame: String, val hash: String?, val reason: String)

data class FlushReport(val hash: String?, val uploads: List<SozuUpload>)

/** Cheques signed offline. The first phone with a connection submits them. */
object SozuPending {
    private const val ENDPOINT = "https://dev.sozu.capital/api/offline/submit"
    private const val RESOLVE = "https://dev.sozu.capital/api/wallet/resolve-recipient"
    private const val BALANCE = "https://dev.sozu.capital/api/wallet/stellar/balance?publicKey="

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

    /**
     * Pushes queued cheques once this phone has internet.
     * A settled cheque and a note the vault already spent leave the queue.
     * A transport failure stays queued for the next connection.
     */
    fun flush(context: Context, settler: String): FlushReport {
        if (!online(context) || !isValidStellarAddress(settler)) return FlushReport(null, emptyList())
        val prefs = context.getSharedPreferences("sozu-pay", Context.MODE_PRIVATE)
        val current = pending(prefs)
        val left = JSONArray()
        val uploads = ArrayList<SozuUpload>(current.length())
        var hash: String? = null
        for (i in 0 until current.length()) {
            val frame = current.optString(i)
            val upload = postFrame(frame, settler)
            uploads.add(upload)
            if (upload.reason == "settled" && upload.hash != null) {
                hash = upload.hash
            } else if (upload.reason == "rejected" || upload.reason == "passkey") {
                left.put(frame)
            }
        }
        prefs.edit().putString("pending", left.toString()).apply()
        Log.i("SozuPay", "flush uploads=${uploads.size} left=${left.length()} hash=${hash ?: "-"}")
        return FlushReport(hash, uploads)
    }

    /** USDC on the same passkey account dev.sozu.capital shows. Null when the phone cannot read it. */
    fun fetchBalanceMinor(address: String): Long? {
        if (!isValidStellarAddress(address)) return null
        val connection = (URL(BALANCE + address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            usdcMinorFromBalanceJson(connection.inputStream.bufferedReader().readText())
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
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

    private fun postFrame(frame: String, settler: String): SozuUpload {
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
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.readText().orEmpty()
            val hash = runCatching { JSONObject(text).optString("hash") }.getOrNull()?.takeIf { it.length >= 16 }
            val reason = sozuUploadReason(text, code in 200..299, hash)
            Log.i("SozuPay", "upload code=$code reason=$reason wallet=${settler.take(4)} body=${text.take(180)}")
            SozuUpload(frame, hash, reason)
        } catch (error: Exception) {
            Log.w("SozuPay", "upload failed ${error.message}")
            SozuUpload(frame, null, "rejected")
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
