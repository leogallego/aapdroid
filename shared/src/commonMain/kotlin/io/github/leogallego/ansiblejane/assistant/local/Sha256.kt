package io.github.leogallego.ansiblejane.assistant.local

/**
 * Incremental SHA-256 for streaming model downloads.
 * Platform MessageDigest on Android/JVM (common API via expect/actual).
 */
expect class Sha256Hasher() {
    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)
    fun digestHex(): String
}

fun sha256Hex(bytes: ByteArray): String {
    val hasher = Sha256Hasher()
    hasher.update(bytes)
    return hasher.digestHex()
}
