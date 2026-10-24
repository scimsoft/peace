package com.scimsoft.peace

import android.os.Build
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Crypto for owner master-unlock: ECDSA P-256 verify + PBKDF2 password check.
 *
 * Limits: public key and password hash are extractable from the APK. Public-key
 * extraction does not help forge signatures. Password hash enables offline
 * guessing — use a strong password and prefer challenge–response.
 */
object OwnerUnlockCrypto {

    private const val EC_ALG = "EC"
    private const val SIG_DER = "SHA256withECDSA"
    private const val SIG_P1363 = "SHA256withECDSAinP1363Format"
    private const val PBKDF2 = "PBKDF2WithHmacSHA256"

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().lowercase().replace(" ", "")
        require(clean.length % 2 == 0) { "hex length must be even" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }

    /**
     * Verify an owner response over [payload] (raw challenge bytes).
     * [responseRawRs] is IEEE P1363 r||s (64 bytes for P-256).
     */
    fun verifyEcdsaP256(payload: ByteArray, responseRawRs: ByteArray): Boolean {
        if (!OwnerUnlockConfig.isChallengeResponseConfigured()) return false
        if (responseRawRs.size != 64) return false
        return try {
            val pubBytes = hexToBytes(OwnerUnlockConfig.OWNER_PUBLIC_KEY_X509_HEX)
            val publicKey = KeyFactory.getInstance(EC_ALG)
                .generatePublic(X509EncodedKeySpec(pubBytes))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val sig = Signature.getInstance(SIG_P1363)
                sig.initVerify(publicKey)
                sig.update(payload)
                sig.verify(responseRawRs)
            } else {
                val der = rawP1363ToDer(responseRawRs)
                val sig = Signature.getInstance(SIG_DER)
                sig.initVerify(publicKey)
                sig.update(payload)
                sig.verify(der)
            }
        } catch (_: Exception) {
            false
        }
    }

    fun verifyMasterPassword(password: CharArray): Boolean {
        if (!OwnerUnlockConfig.isMasterPasswordConfigured()) return false
        return try {
            val salt = hexToBytes(OwnerUnlockConfig.MASTER_PASSWORD_SALT_HEX)
            val expected = hexToBytes(OwnerUnlockConfig.MASTER_PASSWORD_HASH_HEX)
            val spec = PBEKeySpec(
                password,
                salt,
                OwnerUnlockConfig.PBKDF2_ITERATIONS,
                expected.size * 8
            )
            val skf = SecretKeyFactory.getInstance(PBKDF2)
            val actual = skf.generateSecret(spec).encoded
            spec.clearPassword()
            MessageDigest.isEqual(actual, expected)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Convert IEEE P1363 (r||s, each 32 bytes) to DER SEQUENCE for pre-API-28 verify.
     */
    fun rawP1363ToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64)
        val r = BigInteger(1, raw.copyOfRange(0, 32))
        val s = BigInteger(1, raw.copyOfRange(32, 64))
        val rBytes = derInteger(r)
        val sBytes = derInteger(s)
        val seqLen = rBytes.size + sBytes.size
        return byteArrayOf(0x30, seqLen.toByte()) + rBytes + sBytes
    }

    private fun derInteger(value: BigInteger): ByteArray {
        var bytes = value.toByteArray() // already two's complement, may include leading 0
        if (bytes.isEmpty()) bytes = byteArrayOf(0)
        return byteArrayOf(0x02, bytes.size.toByte()) + bytes
    }
}
