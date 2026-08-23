package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.protocol.ExportedClientInfo
import io.github.syrou.reaktiv.introspection.protocol.SessionData
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GhostNetworkImportTest {

    private val json = reaktivJson()

    private fun exportWithNetwork(vararg urls: String) = SessionExport(
        sessionId = "session-1",
        exportedAt = 5_000,
        clientInfo = ExportedClientInfo(
            clientId = "client-1",
            clientName = "Test Device",
            platform = "iOS"
        ),
        session = SessionData(
            startTime = 1_000,
            endTime = 4_000,
            actions = emptyList(),
            logicStartedEvents = emptyList(),
            logicCompletedEvents = emptyList(),
            logicFailedEvents = emptyList(),
            network = urls.mapIndexed { index, url ->
                NetworkRequestCapture(
                    id = "req-$index",
                    startedAtMs = 1_000L + index,
                    durationMs = 20,
                    method = "GET",
                    url = url,
                    responseStatus = 200,
                    responseBody = "{\"index\":$index}"
                )
            }
        )
    )

    @Test
    fun `network exchanges survive a session export round trip`() {
        val original = exportWithNetwork(
            "https://api.example.com/tracks",
            "https://api.example.com/albums"
        )

        val decoded = json.decodeFromString<SessionExport>(json.encodeToString(original))

        assertEquals(2, decoded.session.network.size)
        assertEquals(
            listOf("https://api.example.com/tracks", "https://api.example.com/albums"),
            decoded.session.network.map { it.url }
        )
        assertEquals("{\"index\":0}", decoded.session.network[0].responseBody)
        assertEquals(200, decoded.session.network[1].responseStatus)
    }

    @Test
    fun `a pre-network export still decodes so older session files keep loading`() {
        val legacy = """
            {
              "version": "3.5",
              "sessionId": "old-session",
              "exportedAt": 1,
              "clientInfo": { "clientId": "c", "clientName": "n", "platform": "p" },
              "session": {
                "startTime": 0,
                "endTime": 1,
                "actions": [],
                "logicStartedEvents": [],
                "logicCompletedEvents": [],
                "logicFailedEvents": []
              }
            }
        """.trimIndent()

        val decoded = json.decodeFromString<SessionExport>(legacy)

        assertTrue(decoded.session.network.isEmpty())
        assertEquals("old-session", decoded.sessionId)
    }
}
