package io.github.syrou.reaktiv.introspection

/**
 * Browsers expose gzip only through the asynchronous Web Streams API, and marshalling a Kotlin
 * `ByteArray` across that boundary byte by byte is far slower than letting the host do the work.
 *
 * The DevTools UI therefore inflates a session while it reads the file, on the JavaScript side,
 * and hands Kotlin the JSON text that comes out. Nothing on wasm needs to compress, since exports
 * are written by the capturing device rather than by the UI.
 */
public actual suspend fun gzipCompress(data: ByteArray): ByteArray {
    throw UnsupportedOperationException(
        "gzipCompress is not available on wasmJs. Sessions are compressed by the capturing device."
    )
}

public actual suspend fun gzipDecompress(data: ByteArray): ByteArray {
    throw UnsupportedOperationException(
        "gzipDecompress is not available on wasmJs. Session files are inflated while they are read, " +
            "see the DevTools UI file picker."
    )
}
