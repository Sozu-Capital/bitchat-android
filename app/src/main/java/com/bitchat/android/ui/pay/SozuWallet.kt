package com.bitchat.android.ui.pay

import android.app.Activity
import android.content.Context
import java.security.MessageDigest

/**
 * The passkey wallet on dev.sozu.capital and the offline vault are two accounts
 * for the same person. The wallet holds the USDC. The vault holds the collateral
 * that can be spent offline. A linked phone names the passkey account as the
 * cheque owner. The device key still signs. Until a link arrives, the phone
 * announces a C address derived from its device key.
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
        val saved = prefs.getString("account", null)?.takeIf(::isValidStellarAddress)
        if (saved != null) return saved
        val contractId = MessageDigest.getInstance("SHA-256").digest(signer.compressedPublicKey())
        val account = StellarStrKey.encodeContract(contractId)
        prefs.edit()
            .putString("account", account)
            .putString("owner", account)
            .apply()
        return account
    }

    /** Remembers the passkey wallet that can fund the vault. The device key still signs cheques. */
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
