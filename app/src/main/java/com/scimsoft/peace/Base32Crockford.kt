package com.scimsoft.peace

/**
 * Crockford Base32 — unambiguous for reading aloud (no I/L/O/U).
 * Grouped with dashes for UI; dashes/spaces ignored on decode.
 */
object Base32Crockford {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun encode(data: ByteArray, groupEvery: Int = 4): String {
        if (data.isEmpty()) return ""
        var buffer = 0
        var bitsLeft = 0
        val out = StringBuilder()
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1f
                bitsLeft -= 5
                out.append(ALPHABET[index])
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1f
            out.append(ALPHABET[index])
        }
        val raw = out.toString()
        if (groupEvery <= 0) return raw
        return raw.chunked(groupEvery).joinToString("-")
    }

    fun decode(grouped: String): ByteArray {
        val filtered = buildString {
            for (ch in grouped) {
                when (val c = ch.uppercaseChar()) {
                    '-', ' ', '\t', '\n', '\r' -> Unit
                    'O' -> append('0')
                    'I', 'L' -> append('1')
                    'U' -> Unit // Crockford: U unused; ignore
                    in '0'..'9', in 'A'..'Z' -> {
                        if (c in ALPHABET) append(c)
                        else error("Invalid Base32 char: $ch")
                    }
                    else -> error("Invalid Base32 char: $ch")
                }
            }
        }
        if (filtered.isEmpty()) return ByteArray(0)
        var buffer = 0
        var bitsLeft = 0
        val out = ArrayList<Byte>()
        for (ch in filtered) {
            val value = ALPHABET.indexOf(ch)
            require(value >= 0) { "Invalid Base32 char: $ch" }
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add(((buffer shr (bitsLeft - 8)) and 0xff).toByte())
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }
}
