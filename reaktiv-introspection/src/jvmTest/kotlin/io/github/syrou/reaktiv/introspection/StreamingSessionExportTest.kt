package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ACTIONS = 5_000

private const val PAYLOAD_ITEMS = 200

class StreamingSessionExportTest {

    private val json = reaktivJson(encodeDefaults = true)

    private val serializers = SerializersModule {
        polymorphic(ModuleState::class) {
            subclass(LargeState::class)
        }
    }

    @Serializable
    data class LargeState(val revision: Int = 0, val payload: String = "") : ModuleState

    object LargeModule
    data class Update(val revision: Int) : ModuleAction(LargeModule::class)

    private fun payload(revision: Int): String = buildString {
        repeat(PAYLOAD_ITEMS) { append("item-$revision-$it,") }
    }

    private fun SessionExport.withoutExportStamp(): SessionExport =
        copy(sessionId = "", exportedAt = 0, session = session.copy(endTime = 0))

    private suspend fun largeSession(): SessionCapture {
        val capture = SessionCapture()
        capture.start("device-1", "Pixel", "Android 14")
        capture.attachStateSerializers(serializers)
        capture.captureInitialState(mapOf("large" to LargeState()))
        repeat(ACTIONS) { revision ->
            capture.captureDispatchedAction(Update(revision), LargeState(revision, payload(revision)))
        }
        capture.recordNetworkExchange(
            NetworkRequestCapture(
                id = "req-1",
                startedAtMs = 1_000,
                durationMs = 42,
                method = "GET",
                url = "https://api.example.com/tracks?token=secret",
                responseStatus = 200,
                responseBody = """{"password":"hunter2","tracks":[1,2,3]}"""
            )
        )
        capture.captureLog("INFO", "auth", "signed in with token=secret")
        capture.addMarker("checkpoint", "after the large burst")
        capture.flush()
        return capture
    }

    @Test
    fun `a large session streams into its file as the gzipped json exportSession returns`() = runTest {
        val capture = largeSession()
        val file = Path(SystemTemporaryDirectory, "reaktiv-streamed-session-${Random.nextLong()}.json.gz")
        try {
            val export = capture.buildExport()
            capture.writeSessionFile(export, SystemFileSystem.sink(file))

            val fileBytes = SystemFileSystem.source(file).buffered().use { it.readByteArray() }
            val fileJson = decodeSessionBytes(fileBytes)

            assertTrue(isGzip(fileBytes), "a session file must stay gzip so the ghost import inflates it")
            assertTrue(fileJson.length > ACTIONS * PAYLOAD_ITEMS * 10, "the fixture must be a large session")
            assertTrue(fileBytes.size * 5 < fileJson.length, "the streamed file must be compressed")
            assertEquals(json.encodeToString(SessionExport.serializer(), export), fileJson)

            val fromFile = json.decodeFromString(SessionExport.serializer(), fileJson)
            val fromString = json.decodeFromString(SessionExport.serializer(), capture.exportSession())

            assertEquals(ACTIONS, fromFile.session.actions.size)
            assertEquals(1, fromFile.session.network.size)
            assertEquals(1, fromFile.session.logs.size)
            assertEquals(1, fromFile.session.markers.size)
            assertEquals(fromString.withoutExportStamp(), fromFile.withoutExportStamp())
        } finally {
            SystemFileSystem.delete(file, mustExist = false)
            capture.stop()
        }
    }
}
