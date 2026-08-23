package io.github.syrou.reaktiv.introspection

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.memcpy

public actual class SessionFileExport actual constructor(private val platformContext: PlatformContext) {

    @OptIn(ExperimentalForeignApi::class)
    public actual fun saveToDownloads(bytes: ByteArray, fileName: String): String {
        val fileManager = NSFileManager.defaultManager
        val urls = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
        val documentsUrl = urls.firstOrNull()
            ?: throw Exception("Failed to find Documents directory")

        @Suppress("UNCHECKED_CAST")
        val documentsPath = (documentsUrl as platform.Foundation.NSURL).path
            ?: throw Exception("Failed to get Documents path")

        val filePath = "$documentsPath/$fileName"

        val success = bytes.toNSData().writeToFile(filePath, atomically = true)

        if (!success) {
            throw Exception("Failed to write session file to $filePath")
        }

        return filePath
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun ByteArray.toNSData(): NSData {
        if (isEmpty()) return NSData()
        return usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
    }
}
