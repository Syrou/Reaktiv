package io.github.syrou.reaktiv.introspection

import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

public actual class SessionFileExport actual constructor(private val platformContext: PlatformContext) {

    public actual fun saveToDownloads(bytes: ByteArray, fileName: String): String =
        save(fileName) { file -> file.buffered().use { it.write(bytes) } }

    internal actual suspend fun saveToDownloads(fileName: String, write: suspend (RawSink) -> Unit): String =
        save(fileName) { write(it) }

    private inline fun save(fileName: String, write: (RawSink) -> Unit): String {
        val documentsPath = documentsPath()
        val target = Path(documentsPath, fileName)
        val partial = Path(documentsPath, "$fileName.part")
        try {
            SystemFileSystem.sink(partial).use(write)
            SystemFileSystem.atomicMove(partial, target)
        } catch (e: Throwable) {
            runCatching { SystemFileSystem.delete(partial, mustExist = false) }
            throw e
        }
        return target.toString()
    }

    private fun documentsPath(): String {
        val documentsUrl = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
            .firstOrNull() as? NSURL
            ?: throw Exception("Failed to find Documents directory")
        return documentsUrl.path ?: throw Exception("Failed to get Documents path")
    }
}
