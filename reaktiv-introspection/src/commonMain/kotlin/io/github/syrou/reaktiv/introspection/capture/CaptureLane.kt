package io.github.syrou.reaktiv.introspection.capture

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

internal class CaptureLane<T>(
    private val storage: CaptureStorage,
    private val serializer: KSerializer<T>,
    private val json: Json
) {
    private val pending = ArrayList<String>()

    fun add(value: T) {
        pending += json.encodeToString(serializer, value)
    }

    fun flush() {
        if (pending.isEmpty()) return
        storage.appendLines(pending)
        pending.clear()
    }

    fun trimAbove(cap: Int?): Int {
        if (cap == null) return 0
        val lines = storage.lineCount()
        if (lines <= cap + cap / 4) return 0
        storage.trimTo(cap)
        return lines - cap
    }

    fun lineCount(): Int = storage.lineCount()

    fun lines(): List<String> = storage.readLines()

    fun decode(line: String): T = json.decodeFromString(serializer, line)

    fun read(): List<T> = lines().map(::decode)

    fun replace(lines: List<String>) {
        storage.clear()
        if (lines.isNotEmpty()) storage.appendLines(lines)
    }

    fun clear() {
        pending.clear()
        storage.clear()
    }
}
