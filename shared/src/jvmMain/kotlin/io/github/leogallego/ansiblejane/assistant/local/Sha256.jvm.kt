package io.github.leogallego.ansiblejane.assistant.local

import java.security.MessageDigest

actual class Sha256Hasher {
    private val digest = MessageDigest.getInstance("SHA-256")

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        digest.update(bytes, offset, length)
    }

    actual fun digestHex(): String =
        digest.digest().joinToString("") { byte ->
            val v = byte.toInt() and 0xff
            HEX_DIGITS[v shr 4].toString() + HEX_DIGITS[v and 0xf]
        }
}

private const val HEX_DIGITS = "0123456789abcdef"
