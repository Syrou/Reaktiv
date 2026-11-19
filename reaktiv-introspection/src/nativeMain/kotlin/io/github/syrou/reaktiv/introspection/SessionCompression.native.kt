package io.github.syrou.reaktiv.introspection

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import platform.zlib.Z_BUF_ERROR
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
private class GzipSink(private val downstream: RawSink) : RawSink {
    private val stream = nativeHeap.alloc<z_stream>()
    private val input = ByteArray(CHUNK)
    private val output = ByteArray(CHUNK)
    private val compressed = Buffer()
    private var closed = false

    init {
        val init = deflateInit2(
            strm = stream.ptr,
            level = DEFAULT_LEVEL,
            method = DEFLATED,
            windowBits = GZIP_WINDOW_BITS,
            memLevel = DEFAULT_MEM_LEVEL,
            strategy = DEFAULT_STRATEGY
        )
        if (init != Z_OK) {
            nativeHeap.free(stream)
            throw IllegalStateException("deflateInit2 failed with $init")
        }
    }

    override fun write(source: Buffer, byteCount: Long) {
        check(!closed) { "closed" }
        require(source.size >= byteCount) { "source.size=${source.size} < byteCount=$byteCount" }
        var remaining = byteCount
        while (remaining > 0) {
            val read = source.readAtMostTo(input, 0, minOf(remaining, CHUNK.toLong()).toInt())
            deflateInput(read, Z_NO_FLUSH)
            remaining -= read
        }
    }

    override fun flush() {
        downstream.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            deflateInput(0, Z_FINISH)
        } finally {
            deflateEnd(stream.ptr)
            nativeHeap.free(stream)
            downstream.close()
        }
    }

    private fun deflateInput(length: Int, flush: Int) {
        input.usePinned { pinnedInput ->
            stream.next_in = pinnedInput.addressOf(0).reinterpret<UByteVar>()
            stream.avail_in = length.toUInt()
            do {
                output.usePinned { pinnedOutput ->
                    stream.next_out = pinnedOutput.addressOf(0).reinterpret<UByteVar>()
                    stream.avail_out = CHUNK.toUInt()
                    val result = deflate(stream.ptr, flush)
                    check(result == Z_OK || result == Z_STREAM_END || result == Z_BUF_ERROR) {
                        "deflate failed with $result"
                    }
                }
                val produced = CHUNK - stream.avail_out.toInt()
                if (produced > 0) {
                    compressed.write(output, 0, produced)
                    downstream.write(compressed, compressed.size)
                }
            } while (stream.avail_out.toInt() == 0)
        }
    }
}

public actual suspend fun gzipCompress(data: ByteArray): ByteArray {
    val out = Buffer()
    GzipSink(out).buffered().use { it.write(data) }
    return out.readByteArray()
}

internal actual suspend fun gzipTo(destination: RawSink, write: suspend (Sink) -> Unit) {
    GzipSink(destination).buffered().use { write(it) }
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
