package io.github.syrou.reaktiv.introspection

import java.util.Base64

public actual suspend fun encodeSessionPayload(json: String): String =
    Base64.getEncoder().encodeToString(gzipCompress(json.encodeToByteArray()))

public actual suspend fun decodeSessionPayload(payload: String): String {
    if (isPlainSessionJson(payload)) return payload
    return gzipDecompress(Base64.getDecoder().decode(payload)).decodeToString()
}
