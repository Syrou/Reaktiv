package io.github.syrou.reaktiv.introspection

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

public actual suspend fun gzipCompress(data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(data.size / 4 + 64)
    GZIPOutputStream(out).use { it.write(data) }
    return out.toByteArray()
}

public actual suspend fun gzipDecompress(data: ByteArray): ByteArray {
    require(isGzip(data)) { "Input is not gzip data" }
    return GZIPInputStream(data.inputStream()).use { it.readBytes() }
}
