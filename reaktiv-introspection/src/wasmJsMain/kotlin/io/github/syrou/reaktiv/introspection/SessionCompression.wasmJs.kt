package io.github.syrou.reaktiv.introspection

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.Promise

private fun gzipPipe(bytes: JsAny, compress: Boolean): Promise<JsString> = js("""
    new Promise(function(resolve) {
        var Stream = compress ? globalThis.CompressionStream : globalThis.DecompressionStream;
        if (typeof Stream === 'undefined') {
            throw new Error((compress ? 'CompressionStream' : 'DecompressionStream') + ' is unavailable in this browser');
        }
        var stream = new Blob([bytes]).stream().pipeThrough(new Stream('gzip'));
        resolve(new Response(stream).arrayBuffer().then(function(buffer) {
            var out = new Uint8Array(buffer);
            var text = '';
            var chunk = 0x8000;
            for (var i = 0; i < out.length; i += chunk) {
                text += String.fromCharCode.apply(null, out.subarray(i, Math.min(i + chunk, out.length)));
            }
            return btoa(text);
        }));
    })
""")

@OptIn(ExperimentalEncodingApi::class)
private suspend fun pipe(data: ByteArray, compress: Boolean): ByteArray =
    Base64.decode(gzipPipe(base64ToBytes(Base64.encode(data)), compress).awaitJs().toString())

public actual suspend fun gzipCompress(data: ByteArray): ByteArray = pipe(data, compress = true)

public actual suspend fun gzipDecompress(data: ByteArray): ByteArray {
    require(isGzip(data)) { "Input is not gzip data" }
    return pipe(data, compress = false)
}
