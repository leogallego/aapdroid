package io.github.leogallego.ansiblejane.assistant.local

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

internal actual object LocalModelFiles {
    actual fun ensureDirectory(path: String) {
        File(path).mkdirs()
    }

    actual fun exists(path: String): Boolean = File(path).exists()

    actual fun length(path: String): Long {
        val file = File(path)
        return if (file.isFile) file.length() else 0L
    }

    actual fun deleteRecursively(path: String) {
        File(path).deleteRecursively()
    }

    actual fun rename(from: String, to: String) {
        val source = File(from)
        val target = File(to)
        target.parentFile?.mkdirs()
        if (target.exists()) {
            target.delete()
        }
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    actual fun openSink(path: String, append: Boolean): ModelFileSink {
        val file = File(path)
        file.parentFile?.mkdirs()
        val out = FileOutputStream(file, append)
        return object : ModelFileSink {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                out.write(bytes, offset, length)
            }

            override fun close() {
                out.close()
            }
        }
    }

    actual fun openSource(path: String): ModelFileSource {
        val input = FileInputStream(File(path))
        return object : ModelFileSource {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                input.read(bytes, offset, length)

            override fun close() {
                input.close()
            }
        }
    }

    actual fun join(parent: String, vararg parts: String): String {
        var file = File(parent)
        for (part in parts) {
            file = File(file, part)
        }
        return file.path
    }
}
