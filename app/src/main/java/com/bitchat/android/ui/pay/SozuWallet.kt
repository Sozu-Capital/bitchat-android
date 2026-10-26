package com.bitchat.android.ui.pay

import android.app.Activity
import android.content.Context
import java.security.MessageDigest

/**
 * USDC on Sozu is held by a smart account. The phone announces a C address
 * derived from its passkey. A classic G account is still accepted from a peer
 * so an older phone can be paid. The cheque keeps the same 32-byte id.
 */
object SozuWallet {
    fun ensure(context: Context, activity: Activity): String {
        val signer = DeviceChequeSigner(activity)
        signer.prepare()
        val contractId = MessageDigest.getInstance("SHA-256").digest(signer.compressedPublicKey())
        val account = StellarStrKey.encodeContract(contractId)
        context.getSharedPreferences("sozu-pay", Context.MODE_PRIVATE)
            .edit()
            .putString("account", account)
            .putString("owner", account)
            .apply()
        return account
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
