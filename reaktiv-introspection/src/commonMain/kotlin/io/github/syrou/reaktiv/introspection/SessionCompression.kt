package io.github.syrou.reaktiv.introspection

/**
 * Gzip codec used for session exports.
 *
 * Session JSON is dominated by repeated keys, repeated urls and header names, and JSON response
 * bodies, all of which deflate compresses heavily, so exports are stored and transferred gzipped
 * and inflated once on import.
 *
 * Both functions suspend because the only gzip primitive available in a browser is the Web Streams
 * API, which is asynchronous. Every other platform implements them without suspending.
 *
 * Usage:
 * ```kotlin
 * val bytes = gzipCompress(exportJson.encodeToByteArray())
 * val json = gzipDecompress(bytes).decodeToString()
 * ```
 */
public expect suspend fun gzipCompress(data: ByteArray): ByteArray

/**
 * Inflates bytes produced by [gzipCompress].
 *
 * @throws IllegalArgumentException when the input is not gzip data
 */
public expect suspend fun gzipDecompress(data: ByteArray): ByteArray

/**
 * True when [data] starts with the gzip magic number.
 *
 * Import paths sniff this so session files written before compression was introduced keep loading
 * without a version check or a separate file extension.
 */
public fun isGzip(data: ByteArray): Boolean =
    data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()

/**
 * Returns the session JSON held in [data], inflating it first when it is gzipped.
 *
 * Accepts both compressed exports and the plain JSON produced by earlier versions.
 */
public suspend fun decodeSessionBytes(data: ByteArray): String =
    if (isGzip(data)) gzipDecompress(data).decodeToString() else data.decodeToString()

/**
 * Encodes session JSON for transport as base64 of its gzipped bytes.
 *
 * The DevTools protocol carries session payloads as JSON string fields, so compressed bytes have to
 * survive a JSON round trip. Base64 costs a third on top of the compressed size, which against a
 * typical gzip ratio still leaves the payload far smaller than the raw JSON it replaces.
 */
public expect suspend fun encodeSessionPayload(json: String): String

/**
 * Reverses [encodeSessionPayload], passing plain JSON through untouched.
 *
 * Payloads written before compression was introduced are plain JSON objects, so anything starting
 * with `{` is returned as-is rather than being treated as base64.
 */
public expect suspend fun decodeSessionPayload(payload: String): String

/**
 * True when [payload] looks like plain JSON rather than an encoded session.
 */
public fun isPlainSessionJson(payload: String): Boolean =
    payload.trimStart().startsWith("{")
