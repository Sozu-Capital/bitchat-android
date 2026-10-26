package com.bitchat.android.ui.pay

import android.app.Activity
import android.content.Context
import java.security.MessageDigest

/**
 * USDC on Sozu is held by the web passkey account.
 * A linked phone spends as that address. Until a link arrives, the phone
 * announces a C address derived from its device key.
 * A classic G account is still accepted from a peer.
 */
object SozuWallet {
    @Volatile
    var pendingLink: String? = null

    fun ensure(context: Context, activity: Activity): String {
        val signer = DeviceChequeSigner(activity)
        signer.prepare()
        val prefs = context.getSharedPreferences("sozu-pay", Context.MODE_PRIVATE)
        val linked = prefs.getString("linked", null)?.takeIf(::isValidStellarAddress)
        if (linked != null) {
            prefs.edit().putString("account", linked).putString("owner", linked).apply()
            return linked
        }
        val contractId = MessageDigest.getInstance("SHA-256").digest(signer.compressedPublicKey())
        val account = StellarStrKey.encodeContract(contractId)
        prefs.edit()
            .putString("account", account)
            .putString("owner", account)
            .apply()
        return account
    }

    /** The phone spends as this web passkey account. The device key still signs cheques. */
    fun useWebWallet(context: Context, address: String) {
        context.getSharedPreferences("sozu-pay", Context.MODE_PRIVATE)
            .edit()
            .putString("linked", address)
            .putString("account", address)
            .putString("owner", address)
            .apply()
    }

    fun loadOrCreate(context: Context): String {
        val existing = context.getSharedPreferences("sozu-pay", Context.MODE_PRIVATE)
            .getString("account", null)
        return existing?.takeIf(::isValidStellarAddress).orEmpty()
    }

    fun addressFromMeshText(content: String): String? {
        val line = content.lineSequence().firstOrNull { it.startsWith("sozu-wallet:") } ?: return null
        return line.removePrefix("sozu-wallet:").trim().uppercase().takeIf(::isValidStellarAddress)
    }

    fun tagFromMeshText(content: String): String? {
        val line = content.lineSequence().firstOrNull { it.startsWith("sozu-tag:") } ?: return null
        return normalizeSozuTag(line.removePrefix("sozu-tag:")).takeIf(::isValidSozuTag)
    }
}
