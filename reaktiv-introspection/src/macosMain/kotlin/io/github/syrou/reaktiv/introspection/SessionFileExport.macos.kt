package io.github.syrou.reaktiv.introspection

public actual class SessionFileExport actual constructor(private val platformContext: PlatformContext) {
    public actual fun saveToDownloads(json: String, fileName: String): String {
        throw UnsupportedOperationException("SessionFileExport is not supported on macOS")
    }
}
