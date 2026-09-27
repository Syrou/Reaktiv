package io.github.syrou.reaktiv.introspection.capture

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
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
 * storage.appendLines(listOf("""{"type":"TestAction"}"""))
 * val lines = storage.readLines()
 * storage.clear()
 * ```
 */
internal interface CaptureStorage {
    fun appendLines(lines: List<String>)
    fun readLines(): List<String>
    fun lineCount(): Int
    fun clear()

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

    override fun appendLines(lines: List<String>) {
        if (lines.isEmpty()) return
        val sink = SystemFileSystem.sink(filePath, append = true).buffered()
        try {
            for (line in lines) {
                sink.writeString(line)
                sink.writeString("\n")
            }
            sink.flush()
        } finally {
            sink.close()
        }
        count += lines.size
    }

    override fun readLines(): List<String> {
        if (!SystemFileSystem.exists(filePath)) return emptyList()
        val source = SystemFileSystem.source(filePath).buffered()
        val text = try {
            source.readString()
        } finally {
            source.close()
        }
        return text.substringBeforeLast('\n', missingDelimiterValue = "").split('\n').filter { it.isNotEmpty() }
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
        appendLines(trimmed)
    }
}

/**
 * In-memory JSONL storage fallback for platforms without filesystem access.
 */
internal class InMemoryCaptureStorage : CaptureStorage {
    private val lines = ArrayDeque<String>()

    override fun appendLines(lines: List<String>) {
        this.lines.addAll(lines)
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
}

/**
 * Creates a [CaptureStorage] instance, using file-backed storage when
 * the filesystem is available and falling back to in-memory storage otherwise.
 *
 * @param name A unique name for this storage (used as file name)
 * @return A [CaptureStorage] instance
 */
internal expect fun createCaptureStorage(name: String): CaptureStorage

internal fun fileCaptureStorage(name: String): CaptureStorage {
    return try {
        val dir = Path(SystemTemporaryDirectory, "reaktiv-introspection")
        if (!SystemFileSystem.exists(dir)) {
            SystemFileSystem.createDirectories(dir)
        }
        val storage = FileCaptureStorage(dir, "$name.jsonl")
        storage.clear()
        storage
    } catch (_: Exception) {
        InMemoryCaptureStorage()
    }
}
