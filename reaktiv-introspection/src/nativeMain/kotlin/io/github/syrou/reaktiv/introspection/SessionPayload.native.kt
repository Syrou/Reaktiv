package io.github.syrou.reaktiv.introspection

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
public actual suspend fun encodeSessionPayload(json: String): String =
    Base64.encode(gzipCompress(json.encodeToByteArray()))

@OptIn(ExperimentalEncodingApi::class)
public actual suspend fun decodeSessionPayload(payload: String): String {
    if (isPlainSessionJson(payload)) return payload
    return gzipDecompress(Base64.decode(payload)).decodeToString()
}
