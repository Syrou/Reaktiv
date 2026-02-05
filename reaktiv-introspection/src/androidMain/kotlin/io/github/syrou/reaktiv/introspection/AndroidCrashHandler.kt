package io.github.syrou.reaktiv.introspection

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import java.io.File
import java.io.FileOutputStream
import kotlin.time.Clock

/**
 * Crash handler for Android that captures session data when an uncaught exception occurs.
 *
 * Usage:
 * ```kotlin
 * val introspectionLogic = store.selectLogic<IntrospectionLogic>()
 * val sessionCapture = introspectionLogic.getSessionCapture()
 * AndroidCrashHandler.install(applicationContext, sessionCapture)
 * ```
 *
 * The crash handler saves session JSON to the device's Downloads folder as
 * `reaktiv_crash_<timestamp>.json`. These files can be imported as ghost
 * devices in the DevTools UI for post-mortem debugging.
 *
 * On Android 10+ (API 29+), uses MediaStore for scoped storage.
 * On older versions, saves directly to Downloads directory.
 *
 * @param context Application context for file storage
 * @param sessionCapture The session capture instance from IntrospectionLogic
 */
class AndroidCrashHandler private constructor(
    private val context: Context,
    private val sessionCapture: SessionCapture
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val json = sessionCapture.exportCrashSession(throwable)
            val timestamp = Clock.System.now().toEpochMilliseconds()
            val fileName = "reaktiv_crash_$timestamp.json"

            val savedPath = saveToDownloads(fileName, json)
            println("Introspection: Crash session saved to $savedPath")
        } catch (e: Exception) {
            println("Introspection: Failed to save crash session - ${e.message}")
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveToDownloads(fileName: String, content: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloadsMediaStore(fileName, content)
        } else {
            saveToDownloadsLegacy(fileName, content)
        }
    }

    private fun saveToDownloadsMediaStore(fileName: String, content: String): String {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw Exception("Failed to create MediaStore entry")

        resolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(content.toByteArray())
        } ?: throw Exception("Failed to open output stream")

        contentValues.clear()
        contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)

        return "Downloads/$fileName"
    }

    @Suppress("DEPRECATION")
    private fun saveToDownloadsLegacy(fileName: String, content: String): String {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsDir, fileName)
        FileOutputStream(file).use { outputStream ->
            outputStream.write(content.toByteArray())
        }
        return file.absolutePath
    }

    companion object {
        private var installed = false

        /**
         * Installs the crash handler.
         *
         * @param context Application context
         * @param sessionCapture Session capture instance from IntrospectionLogic
         */
        fun install(context: Context, sessionCapture: SessionCapture) {
            if (installed) {
                println("Introspection: Crash handler already installed")
                return
            }

            Thread.setDefaultUncaughtExceptionHandler(
                AndroidCrashHandler(context.applicationContext, sessionCapture)
            )
            installed = true
            println("Introspection: Crash handler installed")
        }

        /**
         * Manually saves a session to the Downloads folder.
         * Useful for exporting the current session without a crash.
         *
         * @param context Application context
         * @param sessionCapture Session capture instance
         * @param fileName Optional custom file name (defaults to timestamped name)
         * @return Path where the file was saved
         */
        fun saveSessionToDownloads(
            context: Context,
            sessionCapture: SessionCapture,
            fileName: String? = null
        ): String {
            val json = sessionCapture.exportSession()
            val timestamp = Clock.System.now().toEpochMilliseconds()
            val actualFileName = fileName ?: "reaktiv_session_$timestamp.json"

            return AndroidCrashHandler(context, sessionCapture).saveToDownloads(actualFileName, json)
        }
    }
}
