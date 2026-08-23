package io.github.syrou.reaktiv.introspection

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream

private const val CHUNK = 64 * 1024

private const val GZIP_WINDOW_BITS = 15 + 16

private const val DEFAULT_LEVEL = 6

private const val DEFLATED = 8

private const val DEFAULT_MEM_LEVEL = 8

private const val DEFAULT_STRATEGY = 0

/**
 * Pinning requires a non-empty array, so an empty payload is fed through a one byte scratch
 * buffer with `avail_in` left at zero.
 */
private fun pinnableInput(data: ByteArray): ByteArray = if (data.isEmpty()) ByteArray(1) else data

@OptIn(ExperimentalForeignApi::class)
public actual suspend fun gzipCompress(data: ByteArray): ByteArray = memScoped {
    val stream = alloc<z_stream>()
    val init = deflateInit2(
        strm = stream.ptr,
        level = DEFAULT_LEVEL,
        method = DEFLATED,
        windowBits = GZIP_WINDOW_BITS,
        memLevel = DEFAULT_MEM_LEVEL,
        strategy = DEFAULT_STRATEGY
    )
    check(init == Z_OK) { "deflateInit2 failed with $init" }

    val output = ArrayList<Byte>(data.size / 3 + 64)
    val buffer = ByteArray(CHUNK)
    try {
        pinnableInput(data).usePinned { input ->
            stream.next_in = input.addressOf(0).reinterpret<UByteVar>()
            stream.avail_in = data.size.toUInt()
            do {
                buffer.usePinned { out ->
                    stream.next_out = out.addressOf(0).reinterpret<UByteVar>()
                    stream.avail_out = CHUNK.toUInt()
                    val result = deflate(stream.ptr, Z_FINISH)
                    check(result == Z_OK || result == Z_STREAM_END) { "deflate failed with $result" }
                    val produced = CHUNK - stream.avail_out.toInt()
                    for (i in 0 until produced) output.add(buffer[i])
                }
            } while (stream.avail_out.toInt() == 0)
        }
    } finally {
        deflateEnd(stream.ptr)
    }
    output.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
public actual suspend fun gzipDecompress(data: ByteArray): ByteArray = memScoped {
    require(isGzip(data)) { "Input is not gzip data" }
    val stream = alloc<z_stream>()
    val init = inflateInit2(stream.ptr, GZIP_WINDOW_BITS)
    check(init == Z_OK) { "inflateInit2 failed with $init" }

    val output = ArrayList<Byte>(data.size * 4 + 64)
    val buffer = ByteArray(CHUNK)
    try {
        pinnableInput(data).usePinned { input ->
            stream.next_in = input.addressOf(0).reinterpret<UByteVar>()
            stream.avail_in = data.size.toUInt()
            while (true) {
                var finished = false
                buffer.usePinned { out ->
                    stream.next_out = out.addressOf(0).reinterpret<UByteVar>()
                    stream.avail_out = CHUNK.toUInt()
                    val result = inflate(stream.ptr, Z_NO_FLUSH)
                    check(result == Z_OK || result == Z_STREAM_END) { "inflate failed with $result" }
                    val produced = CHUNK - stream.avail_out.toInt()
                    for (i in 0 until produced) output.add(buffer[i])
                    finished = result == Z_STREAM_END
                }
                if (finished) break
            }
        }
    } finally {
        inflateEnd(stream.ptr)
    }
    output.toByteArray()
}
