package io.github.leogallego.ansiblejane.assistant.local

internal interface ModelFileSink {
    fun write(bytes: ByteArray, offset: Int, length: Int)
    fun close()
}

/** Thin multiplatform file helpers for model download paths. */
internal expect object LocalModelFiles {
    fun ensureDirectory(path: String)
    fun exists(path: String): Boolean
    fun length(path: String): Long
    fun deleteRecursively(path: String)
    fun rename(from: String, to: String)
    fun openSink(path: String): ModelFileSink
    fun join(parent: String, vararg parts: String): String
}
