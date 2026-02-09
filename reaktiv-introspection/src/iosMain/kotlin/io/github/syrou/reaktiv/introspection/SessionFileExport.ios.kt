package io.github.syrou.reaktiv.introspection

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

actual class SessionFileExport actual constructor(private val platformContext: PlatformContext) {

    actual fun saveToDownloads(json: String, fileName: String): String {
        val fileManager = NSFileManager.defaultManager
        val urls = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
        val documentsUrl = urls.firstOrNull()
            ?: throw Exception("Failed to find Documents directory")

        @Suppress("UNCHECKED_CAST")
        val documentsPath = (documentsUrl as platform.Foundation.NSURL).path
            ?: throw Exception("Failed to get Documents path")

        val filePath = "$documentsPath/$fileName"

        val nsString = NSString.create(string = json)
        val success = nsString.writeToFile(
            filePath,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null
        )

        if (!success) {
            throw Exception("Failed to write session file to $filePath")
        }

        return filePath
    }
}
