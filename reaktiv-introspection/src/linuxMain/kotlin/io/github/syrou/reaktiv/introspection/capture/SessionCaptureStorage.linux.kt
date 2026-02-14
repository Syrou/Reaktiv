package io.github.syrou.reaktiv.introspection.capture

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

internal actual fun createCaptureStorage(name: String): CaptureStorage {
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
