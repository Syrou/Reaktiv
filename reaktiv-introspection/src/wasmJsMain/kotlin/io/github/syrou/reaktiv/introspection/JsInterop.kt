package io.github.syrou.reaktiv.introspection

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine

internal suspend fun <T : JsAny?> Promise<T>.awaitJs(): T = suspendCancellableCoroutine { continuation ->
    then(
        onFulfilled = { value ->
            continuation.resume(value)
            null
        },
        onRejected = { error ->
            continuation.resumeWithException(IllegalStateException(jsErrorMessage(error)))
            null
        }
    )
}

private fun jsErrorMessage(error: JsAny): String =
    js("(error && error.message) ? String(error.message) : String(error)")

internal fun base64ToBytes(encoded: String): JsAny = js("""{
    var binary = atob(encoded);
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) {
        bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
}""")
