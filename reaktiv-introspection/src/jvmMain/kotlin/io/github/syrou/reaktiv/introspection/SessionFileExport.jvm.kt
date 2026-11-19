package io.github.syrou.reaktiv.introspection

import kotlinx.io.RawSink

public actual class SessionFileExport actual constructor(private val platformContext: PlatformContext) {
    public actual fun saveToDownloads(bytes: ByteArray, fileName: String): String = unsupported()

    internal actual suspend fun saveToDownloads(fileName: String, write: suspend (RawSink) -> Unit): String =
        unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("SessionFileExport is not supported on JVM")
}
