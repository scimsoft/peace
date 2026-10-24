package com.scimsoft.peace

import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Single-use owner unlock challenge.
 *
 * Wire format of payload (signed by the owner tool):
 *   version(1) || timestampMs_be(8) || nonce(16)
 *
 * Display code = Crockford-Base32(payload), grouped for reading aloud.
 * Stored locally so we can enforce expiry + one-time use; the nonce is
 * consumed on successful verify so a response cannot be replayed.
 */
data class OwnerChallenge(
    val payload: ByteArray,
    val displayCode: String,
    val createdAtMs: Long,
    val expiresAtMs: Long
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now > expiresAtMs

    companion object {
        const val VERSION: Byte = 1
        private const val NONCE_LEN = 16
        private const val PAYLOAD_LEN = 1 + 8 + NONCE_LEN // 25

        fun create(now: Long = System.currentTimeMillis()): OwnerChallenge {
            val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
            val buf = ByteBuffer.allocate(PAYLOAD_LEN)
            buf.put(VERSION)
            buf.putLong(now)
            buf.put(nonce)
            val payload = buf.array()
            return OwnerChallenge(
                payload = payload,
                displayCode = Base32Crockford.encode(payload, groupEvery = 4),
                createdAtMs = now,
                expiresAtMs = now + OwnerUnlockConfig.CHALLENGE_TTL_MS
            )
        }

        fun fromStored(payloadHex: String, expiresAtMs: Long): OwnerChallenge? {
            return try {
                val payload = OwnerUnlockCrypto.hexToBytes(payloadHex)
                if (payload.size != PAYLOAD_LEN) return null
                OwnerChallenge(
                    payload = payload,
                    displayCode = Base32Crockford.encode(payload, groupEvery = 4),
                    createdAtMs = ByteBuffer.wrap(payload, 1, 8).long,
                    expiresAtMs = expiresAtMs
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OwnerChallenge) return false
        return payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = payload.contentHashCode()
}

/**
 * High-level owner unlock operations used by the UI.
 */
object OwnerUnlock {

    const val METHOD_CHALLENGE = "challenge"
    const val METHOD_PASSWORD = "password"

    fun issueChallenge(prefs: LockPrefs): OwnerChallenge {
        val challenge = OwnerChallenge.create()
        prefs.ownerChallengePayloadHex = OwnerUnlockCrypto.bytesToHex(challenge.payload)
        prefs.ownerChallengeExpiresAtMs = challenge.expiresAtMs
        return challenge
    }

    fun currentChallenge(prefs: LockPrefs): OwnerChallenge? {
        val hex = prefs.ownerChallengePayloadHex
        if (hex.isBlank()) return null
        val c = OwnerChallenge.fromStored(hex, prefs.ownerChallengeExpiresAtMs) ?: return null
        if (c.isExpired()) {
            clearChallenge(prefs)
            return null
        }
        return c
    }

    fun clearChallenge(prefs: LockPrefs) {
        prefs.ownerChallengePayloadHex = ""
        prefs.ownerChallengeExpiresAtMs = 0L
    }

    /**
     * Verify response code (Crockford Base32 of 64-byte P1363 signature) against
     * the stored challenge; consume nonce on success.
     */
    fun verifyResponse(prefs: LockPrefs, responseGrouped: String): Boolean {
        val challenge = currentChallenge(prefs) ?: return false
        if (challenge.isExpired()) {
            clearChallenge(prefs)
            return false
        }
        val raw = try {
            Base32Crockford.decode(responseGrouped)
        } catch (_: Exception) {
            return false
        }
        val ok = OwnerUnlockCrypto.verifyEcdsaP256(challenge.payload, raw)
        if (ok) {
            // One-time: consume nonce so this response cannot be reused.
            clearChallenge(prefs)
        }
        return ok
    }

    fun verifyPassword(password: CharArray): Boolean =
        OwnerUnlockCrypto.verifyMasterPassword(password)
}
