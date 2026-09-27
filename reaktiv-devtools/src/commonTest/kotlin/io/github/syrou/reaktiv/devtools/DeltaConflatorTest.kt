package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.devtools.service.DeltaConflator
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.DeltaKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DeltaConflatorTest {

    private fun delta(module: String, type: String, json: String, kind: DeltaKind = DeltaKind.FULL) = CapturedAction(
        clientId = "device",
        timestamp = 0L,
        actionType = type,
        actionData = "{}",
        stateDeltaJson = json,
        moduleName = module,
        deltaKind = kind
    )

    @Test
    fun `a delta that arrives while a batch is being sent goes out after it`() = runTest {
        val sent = mutableListOf<String>()
        val sending = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val conflator = DeltaConflator(backgroundScope, 75L) { event ->
            if (event.actionType == "First") {
                sending.complete(Unit)
                release.await()
            }
            sent += event.actionType
        }

        conflator.offer(delta("Counter", "First", """{"count":1}"""))
        sending.await()
        conflator.offer(delta("Profile", "Second", """{"name":"a"}"""))
        release.complete(Unit)
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf("First", "Second"), sent)
    }

    @Test
    fun `a burst for one module goes out as one delta`() = runTest {
        val sent = mutableListOf<CapturedAction>()
        val conflator = DeltaConflator(backgroundScope, 75L) { sent += it }

        conflator.offer(delta("Counter", "Set", """{"count":1}"""))
        conflator.offer(delta("Counter", "Increment", """{"count":2}""", DeltaKind.FIELDS))
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(1, sent.size)
        assertEquals("""{"count":2}""", sent.single().stateDeltaJson)
    }
}
