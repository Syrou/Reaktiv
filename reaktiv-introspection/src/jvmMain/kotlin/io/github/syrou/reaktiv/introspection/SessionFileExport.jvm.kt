package io.github.syrou.reaktiv.introspection

actual class SessionFileExport actual constructor(private val platformContext: PlatformContext) {
    actual fun saveToDownloads(json: String, fileName: String): String {
        throw UnsupportedOperationException("SessionFileExport is not supported on JVM")
    }
}
