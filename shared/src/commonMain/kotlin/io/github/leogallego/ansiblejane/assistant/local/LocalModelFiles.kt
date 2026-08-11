package io.github.leogallego.ansiblejane.assistant.local

internal interface ModelFileSink {
    fun write(bytes: ByteArray, offset: Int, length: Int)
    fun close()
}

internal interface ModelFileSource {
    /** Reads up to [length] bytes into [bytes] at [offset]. Returns count, or -1 at EOF. */
    fun read(bytes: ByteArray, offset: Int, length: Int): Int
    fun close()
}

/** Thin multiplatform file helpers for model download paths. */
internal expect object LocalModelFiles {
    fun ensureDirectory(path: String)
    fun exists(path: String): Boolean
    fun length(path: String): Long
    fun deleteRecursively(path: String)
    fun rename(from: String, to: String)
    fun openSink(path: String, append: Boolean = false): ModelFileSink
    fun openSource(path: String): ModelFileSource
    fun join(parent: String, vararg parts: String): String
    /**
     * Best-effort lookup of [fileName] under the user Downloads directory.
     * Returns an absolute path when the file exists, is readable, and non-empty.
     */
    fun findInUserDownloads(fileName: String): String?
}
