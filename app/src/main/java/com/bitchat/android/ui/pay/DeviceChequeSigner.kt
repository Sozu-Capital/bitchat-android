package com.bitchat.android.ui.pay

import android.app.Activity
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Device key in the Android keystore. The passkey prompt authorizes the signature.
 * The private key never leaves the phone. The cheque digest is SHA-256 once.
 */
class DeviceChequeSigner(private val activity: Activity) {
    private val alias = "sozu-device-p256"
    private val executor = Executors.newSingleThreadExecutor()

    fun prepare() {
        ensureKey()
    }

    fun compressedPublicKey(): ByteArray {
        ensureKey()
        val cert = keyStore().getCertificate(alias)
        val point = (cert.publicKey as ECPublicKey).w
        val x = fixed32(point.affineX.toByteArray())
        val y = point.affineY
        val prefix: Byte = if (y.testBit(0)) 0x03 else 0x02
        return byteArrayOf(prefix) + x
    }

    suspend fun sign(body: ByteArray): ByteArray {
        ensureKey()
        val privateKey = keyStore().getKey(alias, null) as java.security.PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        val info = java.security.KeyFactory.getInstance(privateKey.algorithm, "AndroidKeyStore")
            .getKeySpec(privateKey, android.security.keystore.KeyInfo::class.java)
        if (info.isUserAuthenticationRequired && Build.VERSION.SDK_INT >= 28) {
            authenticate(signature)
        }
        signature.update(body)
        return SozuCheque.derToLowS(signature.sign())
    }

    private fun ensureKey() {
        val store = keyStore()
        if (store.containsAlias(alias)) return
        try {
            generate(requireAuth = Build.VERSION.SDK_INT >= 30)
        } catch (_: Exception) {
            runCatching { store.deleteEntry(alias) }
            generate(requireAuth = false)
        }
    }

    private fun generate(requireAuth: Boolean) {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
        if (requireAuth && Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        }
        generator.initialize(builder.build())
        generator.generateKeyPair()
    }

    private suspend fun authenticate(signature: Signature) {
        if (Build.VERSION.SDK_INT < 28) return
        suspendCancellableCoroutine { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle("Confirm with passkey")
                .setSubtitle("Sign this Sozu cheque")
                .apply {
                    if (Build.VERSION.SDK_INT >= 30) {
                        setAllowedAuthenticators(
                            android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                        )
                    } else {
                        setNegativeButton("Cancel", activity.mainExecutor) { _, _ -> }
                    }
                }
                .build()
            prompt.authenticate(
                BiometricPrompt.CryptoObject(signature),
                cancel,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException(errString.toString()))
                    }
                },
            )
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun fixed32(raw: ByteArray): ByteArray {
        val trimmed = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        return ByteArray(32 - trimmed.size) + trimmed
    }
}
