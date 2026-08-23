package io.github.syrou.reaktiv.introspection

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val ERROR_PREFIX = "reaktiv-error:"

/**
 * Compresses session JSON to base64 entirely in the host.
 *
 * Only strings cross the wasm boundary. Marshalling a `ByteArray` into a `Uint8Array` byte by byte
 * would be both slow and easy to get subtly wrong, and the host already has `CompressionStream`,
 * so the whole transform happens there and Kotlin sees text on both sides.
 */
private fun gzipToBase64(json: String, callback: (String) -> Unit) {
    js("""
        (function(content, done) {
            if (typeof CompressionStream === 'undefined') {
                done('reaktiv-error:CompressionStream is unavailable in this browser');
                return;
            }
            var stream = new Blob([content]).stream()
                .pipeThrough(new CompressionStream('gzip'));
            new Response(stream).arrayBuffer().then(function(buffer) {
                var bytes = new Uint8Array(buffer);
                var binary = '';
                var chunk = 0x8000;
                for (var i = 0; i < bytes.length; i += chunk) {
                    binary += String.fromCharCode.apply(
                        null, bytes.subarray(i, Math.min(i + chunk, bytes.length))
                    );
                }
                done(btoa(binary));
            }).catch(function(err) {
                done('reaktiv-error:' + (err && err.message ? err.message : 'compression failed'));
            });
        })(json, callback)
    """)
}

private fun base64ToGunzip(payload: String, callback: (String) -> Unit) {
    js("""
        (function(encoded, done) {
            if (typeof DecompressionStream === 'undefined') {
                done('reaktiv-error:DecompressionStream is unavailable in this browser');
                return;
            }
            try {
                var binary = atob(encoded);
                var bytes = new Uint8Array(binary.length);
                for (var i = 0; i < binary.length; i++) {
                    bytes[i] = binary.charCodeAt(i);
                }
                var stream = new Blob([bytes]).stream()
                    .pipeThrough(new DecompressionStream('gzip'));
                new Response(stream).text().then(done).catch(function(err) {
                    done('reaktiv-error:' + (err && err.message ? err.message : 'inflate failed'));
                });
            } catch (err) {
                done('reaktiv-error:' + (err && err.message ? err.message : 'not base64'));
            }
        })(payload, callback)
    """)
}

private suspend fun bridge(
    run: (String, (String) -> Unit) -> Unit,
    input: String
): String = suspendCoroutine { continuation ->
    run(input) { result ->
        if (result.startsWith(ERROR_PREFIX)) {
            continuation.resumeWithException(
                IllegalStateException(result.removePrefix(ERROR_PREFIX))
            )
        } else {
            continuation.resume(result)
        }
    }
}

public actual suspend fun encodeSessionPayload(json: String): String =
    bridge(::gzipToBase64, json)

public actual suspend fun decodeSessionPayload(payload: String): String {
    if (isPlainSessionJson(payload)) return payload
    return bridge(::base64ToGunzip, payload)
}
