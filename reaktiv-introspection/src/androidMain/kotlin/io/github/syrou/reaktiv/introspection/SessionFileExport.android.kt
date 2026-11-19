package io.github.syrou.reaktiv.introspection

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.io.RawSink
import kotlinx.io.asSink
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

public actual class SessionFileExport actual constructor(private val platformContext: PlatformContext) {

    public actual fun saveToDownloads(bytes: ByteArray, fileName: String): String =
        save(fileName) { it.write(bytes) }

    internal actual suspend fun saveToDownloads(fileName: String, write: suspend (RawSink) -> Unit): String =
        save(fileName) { write(it.asSink()) }

    @Suppress("DEPRECATION")
    private inline fun save(fileName: String, write: (OutputStream) -> Unit): String {
        val context = platformContext.context

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, sessionFileMimeType(fileName))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw Exception("Failed to create MediaStore entry")

            try {
                val outputStream = resolver.openOutputStream(uri) ?: throw Exception("Failed to open output stream")
                outputStream.use(write)
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }

            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            return "Downloads/$fileName"
        } else {
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            try {
                FileOutputStream(file).use(write)
            } catch (e: Throwable) {
                file.delete()
                throw e
            }
            return file.absolutePath
        }
    }
}
