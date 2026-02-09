package io.github.syrou.reaktiv.introspection

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

actual class SessionFileExport actual constructor(private val platformContext: PlatformContext) {

    @Suppress("DEPRECATION")
    actual fun saveToDownloads(json: String, fileName: String): String {
        val context = platformContext.context

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw Exception("Failed to create MediaStore entry")

            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(json.toByteArray())
            } ?: throw Exception("Failed to open output stream")

            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            return "Downloads/$fileName"
        } else {
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            FileOutputStream(file).use { outputStream ->
                outputStream.write(json.toByteArray())
            }
            return file.absolutePath
        }
    }
}
