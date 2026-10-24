package com.scimsoft.peace

/**
 * Owner master-unlock configuration (build-time secrets that are safe to ship).
 *
 * SECURITY MODEL
 * --------------
 * 1) Challenge–response (preferred): only the owner's PUBLIC key is embedded here.
 *    The PRIVATE key never lives on the phone. A decompiler can read this public key
 *    but cannot forge a valid response without the private key held in owner-tool/.
 *
 * 2) Master password (fallback): we store only a high-iteration PBKDF2 salt+hash.
 *    Anyone who extracts the hash can run an offline guessing attack — use a long,
 *    random password. Prefer challenge–response when possible.
 *
 * SETUP (see README / owner-tool/README.md):
 *   python3 owner-tool/peace_owner.py keygen
 *   → paste the printed PUBLIC_KEY_X509_HEX into [OWNER_PUBLIC_KEY_X509_HEX]
 *   python3 owner-tool/peace_owner.py password-hash
 *   → paste SALT_HEX / HASH_HEX into the fields below
 *
 * Leave a field empty to disable that method.
 */
object OwnerUnlockConfig {

    /**
     * EC P-256 (secp256r1) public key, X.509 SubjectPublicKeyInfo, lowercase hex.
     * Empty string disables challenge–response unlock.
     */
    const val OWNER_PUBLIC_KEY_X509_HEX: String = ""

    /**
     * PBKDF2-HMAC-SHA256 salt (16+ bytes recommended), lowercase hex.
     * Empty disables master-password unlock.
     */
    const val MASTER_PASSWORD_SALT_HEX: String = ""

    /**
     * PBKDF2-HMAC-SHA256 derived key (32 bytes), lowercase hex.
     */
    const val MASTER_PASSWORD_HASH_HEX: String = ""

    /** Iteration count — must match owner-tool/peace_owner.py. */
    const val PBKDF2_ITERATIONS: Int = 600_000

    /** How long a displayed challenge remains valid. */
    const val CHALLENGE_TTL_MS: Long = 10L * 60L * 1000L

    fun isChallengeResponseConfigured(): Boolean =
        OWNER_PUBLIC_KEY_X509_HEX.isNotBlank()

    fun isMasterPasswordConfigured(): Boolean =
        MASTER_PASSWORD_SALT_HEX.isNotBlank() && MASTER_PASSWORD_HASH_HEX.isNotBlank()

    fun anyOwnerUnlockConfigured(): Boolean =
        isChallengeResponseConfigured() || isMasterPasswordConfigured()
}
