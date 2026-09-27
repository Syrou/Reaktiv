package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
data class SignInState(val signedIn: Boolean = false) : ModuleState

class ExportRedactionTest {

    private val json = reaktivJson(encodeDefaults = true)

    private data class SignIn(val user: String, val password: String) : ModuleAction(SignInState::class)

    private suspend fun session(block: suspend SessionCapture.() -> Unit): Pair<SessionExport, SessionCapture> {
        val capture = SessionCapture()
        capture.start("client-export", "ExportApp", "TestPlatform")
        capture.attachStateSerializers(SerializersModule { polymorphic(ModuleState::class) { subclass(SignInState::class) } })
        capture.block()
        return json.decodeFromString<SessionExport>(capture.exportSession()) to capture
    }

    @Test
    fun `an action payload keeps its shape and loses its secrets`() = runTest {
        val (export, capture) = session {
            captureDispatchedAction(SignIn("bob", "hunter2"), SignInState(signedIn = true))
        }

        val live = capture.getSessionHistory().actions.single().actionData
        val exported = export.session.actions.single().actionData
        assertTrue("hunter2" in live, "live DevTools shows the real payload, got $live")
        assertEquals("SignIn(user=bob, password=[REDACTED])", exported)
        capture.stop()
    }

    @Test
    fun `a network exchange is masked in headers and query and json and form bodies`() = runTest {
        val exchange = NetworkRequestCapture(
            id = "req-1",
            startedAtMs = 0,
            durationMs = 5,
            method = "POST",
            url = "https://api.example.com/login?page=2&access_token=abc123#top",
            requestHeaders = mapOf(
                "Authorization" to listOf("Bearer abc"),
                "X-Session" to listOf("s-1"),
                "X-Custom" to listOf("visible")
            ),
            requestContentType = "application/json",
            requestBody = """{"name":"bob","password":"hunter2","nested":{"apiKey":"k"}}""",
            responseContentType = "application/x-www-form-urlencoded",
            responseBody = "state=ok&refresh_token=r-1",
            sensitiveHeaders = setOf("X-Session")
        )
        val (export, capture) = session { recordNetworkExchange(exchange) }

        assertEquals(exchange.requestHeaders, capture.getSessionHistory().network.single().requestHeaders)

        val masked = export.session.network.single()
        assertEquals("https://api.example.com/login?page=2&access_token=[REDACTED]#top", masked.url)
        assertEquals(listOf("[REDACTED]"), masked.requestHeaders["Authorization"])
        assertEquals(listOf("[REDACTED]"), masked.requestHeaders["X-Session"])
        assertEquals(listOf("visible"), masked.requestHeaders["X-Custom"])
        val body = json.parseToJsonElement(masked.requestBody!!).jsonObject
        assertEquals("bob", body["name"]!!.jsonPrimitive.content)
        assertEquals("[REDACTED]", body["password"]!!.jsonPrimitive.content)
        assertEquals("[REDACTED]", body["nested"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content)
        assertEquals("state=ok&refresh_token=[REDACTED]", masked.responseBody)
        capture.stop()
    }

    @Test
    fun `a log line loses the values of sensitive keys`() = runTest {
        val (export, capture) = session { captureLog("INFO", "Auth", "signed in user=bob token=abc123") }

        val message = export.session.logs.single().message
        assertEquals("signed in user=bob token=[REDACTED]", message)
        capture.stop()
    }

    @Test
    fun `an empty key set leaves key names alone`() = runTest {
        val capture = SessionCapture(sensitiveKeys = emptySet())
        capture.start("client-open", "OpenApp", "TestPlatform")
        capture.captureLog("INFO", "Auth", "token=abc123")
        val export = json.decodeFromString<SessionExport>(capture.exportSession())

        assertFalse("[REDACTED]" in export.session.logs.single().message)
        capture.stop()
    }
}
