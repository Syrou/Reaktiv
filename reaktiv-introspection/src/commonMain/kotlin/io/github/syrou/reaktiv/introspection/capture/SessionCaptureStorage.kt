package io.github.syrou.reaktiv.introspection.capture

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readLine
import kotlinx.io.writeString

/**
 * Abstraction for line-based event storage used by SessionCapture.
 *
 * Two implementations exist:
 * - [FileCaptureStorage]: File-backed JSONL storage using kotlinx-io (JVM, native)
 * - [InMemoryCaptureStorage]: In-memory fallback (wasmJs browser or when filesystem is unavailable)
 *
 * Usage:
 * ```kotlin
 * val storage = createCaptureStorage("actions")
 * storage.appendLine("""{"type":"TestAction"}""")
 * val lines = storage.readLines()
 * storage.clear()
 * ```
 */
internal interface CaptureStorage {
    fun appendLine(line: String)
    fun readLines(): List<String>
    fun lineCount(): Int
    fun clear()
    fun delete()

    /**
     * Trims storage to keep only the most recent [keepCount] lines.
     */
    fun trimTo(keepCount: Int)
}

/**
 * File-backed JSONL storage using kotlinx-io.
 *
 * Each event is stored as a single JSON line in a temporary file.
 */
internal class FileCaptureStorage(
    directory: Path,
    fileName: String
) : CaptureStorage {
    private val filePath = Path(directory, fileName)
    private var count = 0

    override fun appendLine(line: String) {
        val sink = SystemFileSystem.sink(filePath, append = true).buffered()
        try {
            sink.writeString(line)
            sink.writeString("\n")
            sink.flush()
        } finally {
            sink.close()
        }
        count++
    }

    override fun readLines(): List<String> {
        if (!SystemFileSystem.exists(filePath)) return emptyList()
        val result = mutableListOf<String>()
        val source = SystemFileSystem.source(filePath).buffered()
        try {
            while (true) {
                val line = source.readLine() ?: break
                if (line.isNotEmpty()) {
                    result.add(line)
                }
            }
        } finally {
            source.close()
        }
        return result
    }

    override fun lineCount(): Int = count

    override fun clear() {
        if (SystemFileSystem.exists(filePath)) {
            SystemFileSystem.delete(filePath)
        }
        count = 0
    }

    override fun trimTo(keepCount: Int) {
        if (count <= keepCount) return
        val lines = readLines()
        clear()
        val trimmed = if (lines.size > keepCount) {
            lines.subList(lines.size - keepCount, lines.size)
        } else {
            lines
        }
        for (line in trimmed) {
            appendLine(line)
        }
    }

    override fun delete() {
        clear()
    }
}

/**
 * In-memory JSONL storage fallback for platforms without filesystem access.
 */
internal class InMemoryCaptureStorage : CaptureStorage {
    private val lines = ArrayDeque<String>()

    override fun appendLine(line: String) {
        lines.addLast(line)
    }

    override fun readLines(): List<String> = lines.toList()

    override fun lineCount(): Int = lines.size

    override fun clear() {
        lines.clear()
    }

    override fun trimTo(keepCount: Int) {
        while (lines.size > keepCount) {
            lines.removeFirst()
        }
    }

    override fun delete() {
        lines.clear()
    }
}

/**
 * Creates a [CaptureStorage] instance, using file-backed storage when
 * the filesystem is available and falling back to in-memory storage otherwise.
 *
 * @param name A unique name for this storage (used as file name)
 * @return A [CaptureStorage] instance
 */
internal expect fun createCaptureStorage(name: String): CaptureStorage
