package com.coxy.ghost.net.adb

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.interfaces.RSAPublicKey
import java.util.Calendar
import javax.crypto.Cipher
import javax.security.auth.x500.X500Principal

/**
 * GHOST's identity to another phone's debug bridge.
 *
 * The authentication here is the same shape as pairing a television: the other phone remembers a
 * public key and, once its owner has tapped "Allow", lets that key back in without asking again.
 * The difference is that the prompt names a fingerprint and appears on the *other* device — which
 * is the consent gate, and the reason this is safe to build. Nothing GHOST does can reach a phone
 * whose owner has not physically agreed to it.
 *
 * Generated once into the AndroidKeyStore, where the private half cannot be read back out even by
 * this app, and separate from the television key: trusting GHOST with a TV should not be the same
 * decision as trusting her with a phone.
 */
internal object AdbKey {

    private const val TAG = "AdbKey"
    private const val ALIAS = "ghost-adb"

    /** 2048-bit modulus, in 32-bit words, as adb's key format counts it. */
    private const val MODULUS_BYTES = 256
    private const val MODULUS_WORDS = MODULUS_BYTES / 4

    /**
     * The DigestInfo prefix for a SHA-1 hash in a PKCS#1 v1.5 signature.
     *
     * adb hands over a 20-byte token that is *already* a digest and expects it signed as one, so
     * the prefix has to be prepended by hand. There is no "sign this existing digest" path through
     * `Signature`, and using SHA1withRSA over the token would hash it a second time and produce a
     * signature the other phone rejects without ever saying why.
     */
    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
        0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    private fun entry(): KeyStore.PrivateKeyEntry {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return it }

        Log.i(TAG, "generating GHOST's debug-bridge key")
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
        java.security.KeyPairGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            .apply {
                initialize(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setKeySize(2048)
                        .setDigests(
                            KeyProperties.DIGEST_NONE,
                            KeyProperties.DIGEST_SHA1,
                            KeyProperties.DIGEST_SHA256
                        )
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        // PKCS1 *encryption* padding, because the signature is produced by running
                        // the digest through Cipher rather than Signature — the same detail that
                        // stopped the television key working until it was explicitly allowed.
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                        .setRandomizedEncryptionRequired(false)
                        .setCertificateSubject(X500Principal("CN=GHOST"))
                        .setCertificateSerialNumber(BigInteger.ONE)
                        .setCertificateNotAfter(end.time)
                        .build()
                )
                generateKeyPair()
            }
        return ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    /** Sign adb's 20-byte challenge token. */
    fun sign(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, entry().privateKey)
        return cipher.doFinal(SHA1_DIGEST_INFO + token)
    }

    /**
     * The public key, in the format adb expects: a packed little-endian struct, base64'd, with a
     * name after it.
     *
     * Not X.509 and not PEM. adb carries its own Montgomery-friendly representation — modulus and
     * `rr` little-endian, plus `n0inv` — because the bootloader-era C that first parsed it did
     * modular arithmetic directly on those words. Getting the byte order wrong yields a key the
     * other phone accepts and then never matches, so the "Allow" prompt returns every single time
     * and nothing is ever remembered.
     */
    fun publicKeyForAdb(): ByteArray {
        val pub = entry().certificate.publicKey as RSAPublicKey
        val n = pub.modulus

        // n0inv = -1 / n[0] mod 2^32, where n[0] is the low 32-bit word of the modulus.
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toLong() and 0xFFFFFFFFL

        // rr = (2^2048)^2 mod n
        val rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8 * 2).mod(n)

        val buffer = ByteBuffer.allocate(3 * 4 + 2 * MODULUS_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MODULUS_WORDS)
        buffer.putInt(n0inv.toInt())
        buffer.put(littleEndian(n))
        buffer.put(littleEndian(rr))
        buffer.putInt(pub.publicExponent.toInt())

        // The trailing name is what the other phone shows beside the fingerprint. Saying who is
        // asking is the difference between a prompt someone can act on and one they cannot.
        return Base64.encode(buffer.array(), Base64.NO_WRAP) + " GHOST@ghost".toByteArray()
    }

    /** Big-endian [BigInteger] to a fixed-width little-endian byte array. */
    private fun littleEndian(v: BigInteger): ByteArray {
        val be = v.toByteArray().let {
            if (it.size > MODULUS_BYTES) it.copyOfRange(it.size - MODULUS_BYTES, it.size) else it
        }
        val out = ByteArray(MODULUS_BYTES)
        for (i in be.indices) out[be.size - 1 - i] = be[i]
        return out
    }
}
