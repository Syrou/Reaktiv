package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.introspection.capture.SessionCapture

/**
 * Platform-specific session file export.
 *
 * Handles saving session JSON data to the device's file system.
 * On Android, uses MediaStore for scoped storage (API 29+) or direct file access.
 * On iOS, uses NSFileManager to write to the Documents directory.
 * On other platforms, throws [UnsupportedOperationException].
 *
 * Usage:
 * ```kotlin
 * val exporter = SessionFileExport(platformContext)
 * val path = exporter.saveToDownloads(jsonString, "session.json")
 * ```
 *
 * @param platformContext The platform context for file access
 */
public expect class SessionFileExport(platformContext: PlatformContext) {
    public fun saveToDownloads(bytes: ByteArray, fileName: String): String
}

internal fun sessionFileMimeType(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "gz" -> "application/gzip"
        "json" -> "application/json"
        "xml" -> "application/xml"
        else -> "application/octet-stream"
    }

internal suspend fun SessionFileExport.saveSession(
    capture: SessionCapture,
    crash: Throwable? = null,
    fileName: String? = null
): String {
    val json = if (crash == null) capture.exportSession() else capture.exportCrashSession(crash)
    return saveToDownloads(
        gzipCompress(json.encodeToByteArray()),
        fileName ?: capture.suggestFileName(if (crash == null) null else "crash")
    )
}
