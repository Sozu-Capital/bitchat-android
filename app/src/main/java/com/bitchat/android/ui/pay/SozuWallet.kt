package com.bitchat.android.ui.pay

import android.content.Context
import java.security.SecureRandom
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters

/** Stellar account created the first time Sozu opens. The seed stays on this phone. */
object SozuWallet {
    fun loadOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences("sozu-pay", Context.MODE_PRIVATE)
        val existing = prefs.getString("account", null)
        if (existing != null && isValidStellarAddress(existing)) return existing

        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = generator.generateKeyPair()
        val seed = (pair.private as Ed25519PrivateKeyParameters).encoded
        val account = StellarStrKey.encodeAccount((pair.public as Ed25519PublicKeyParameters).encoded)
        prefs.edit()
            .putString("seed", seed.joinToString("") { "%02x".format(it) })
            .putString("account", account)
            .putString("owner", account)
            .apply()
        return account
    }

    fun addressFromMeshText(content: String): String? {
        val line = content.lineSequence().firstOrNull { it.startsWith("sozu-wallet:") } ?: return null
        return line.removePrefix("sozu-wallet:").trim().uppercase().takeIf(::isValidStellarAddress)
    }
}
