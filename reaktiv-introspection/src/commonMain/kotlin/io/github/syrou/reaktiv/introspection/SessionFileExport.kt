package io.github.syrou.reaktiv.introspection

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
expect class SessionFileExport(platformContext: PlatformContext) {
    fun saveToDownloads(json: String, fileName: String): String
}
