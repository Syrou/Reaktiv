package io.github.syrou.reaktiv.introspection.capture

internal actual fun createCaptureStorage(name: String): CaptureStorage = InMemoryCaptureStorage()
