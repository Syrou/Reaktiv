package io.github.syrou.reaktiv.devtools.server

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Serves files from a static root folder.
 */
fun Route.staticFiles(folder: String) {
    val dir = Path(folder)
    val pathParameter = "static-content-path-parameter"

    get("{$pathParameter...}") {
        val relativePath = call.parameters.getAll(pathParameter)?.joinToString("/") ?: return@get
        val file = Path(dir, relativePath)
        call.respondStatic(file)
    }

    get("/") {
        val indexPath = Path(dir, "index.html")
        call.respondStatic(indexPath)
    }
}

/**
 * Responds with a static file.
 */
suspend fun ApplicationCall.respondStatic(path: Path) {
    if (SystemFileSystem.exists(path)) {
        respond(LocalFileContent(path, ContentType.defaultForFile(path)))
    } else {
        respond(HttpStatusCode.NotFound)
    }
}

/**
 * Determines the content type based on file extension.
 */
fun ContentType.Companion.defaultForFile(path: Path): ContentType {
    val extension = path.name.substringAfterLast('.', "")
    val contentType = ContentType.fromFileExtension(extension).firstOrNull()
        ?: ContentType.Application.OctetStream

    return when {
        contentType.contentType == "text" && contentType.charset() == null ->
            contentType.withCharset(Charsets.UTF_8)
        else -> contentType
    }
}

/**
 * OutgoingContent implementation for serving local files.
 */
class LocalFileContent(
    private val path: Path,
    override val contentType: ContentType = ContentType.defaultForFile(path)
) : OutgoingContent.WriteChannelContent() {

    override val contentLength: Long
        get() {
            val metadata = SystemFileSystem.metadataOrNull(path)
            return metadata?.size ?: -1
        }

    override suspend fun writeTo(channel: ByteWriteChannel) {
        val source = SystemFileSystem.source(path)
        source.buffered().use { bufferedSource ->
            val buf = ByteArray(4 * 1024)
            while (true) {
                val read = bufferedSource.readAtMostTo(buf)
                if (read <= 0) break
                channel.writeFully(buf, 0, read)
            }
        }
    }

    init {
        if (!SystemFileSystem.exists(path)) {
            throw IllegalStateException("No such file $path")
        }

        SystemFileSystem.metadataOrNull(path)?.let { metadata ->
            versions += LastModifiedVersion(GMTDate(0))
        }
    }
}
