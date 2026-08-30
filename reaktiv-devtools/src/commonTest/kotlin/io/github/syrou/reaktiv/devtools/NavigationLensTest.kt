package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.devtools.protocol.GUARD_TRACE_CLASS
import io.github.syrou.reaktiv.devtools.protocol.NAVIGATION_TRACE_CLASS
import io.github.syrou.reaktiv.devtools.protocol.buildNavigationLog
import io.github.syrou.reaktiv.devtools.protocol.parseNavigationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationLensTest {

    private val stateJson = """
        {
          "com.example.AuthState": { "isAuthenticated": true },
          "io.github.syrou.reaktiv.navigation.NavigationState": {
            "currentEntry": { "path": "workspace/project/files", "params": { "id": "7" } },
            "backStack": [
              { "path": "home", "params": {} },
              { "path": "workspace/project", "params": {} },
              { "path": "workspace/project/files", "params": { "id": "7" } }
            ],
            "derived": {
              "currentGraphHierarchy": ["root", "workspace", "project"],
              "isCurrentModal": false
            },
            "activeModalContexts": { "workspace/project": {} },
            "isBootstrapping": false,
            "isEvaluatingNavigation": false
          }
        }
    """.trimIndent()

    @Test
    fun `reads the navigation position out of a state tree`() {
        val snapshot = parseNavigationState(stateJson)!!

        assertEquals("io.github.syrou.reaktiv.navigation.NavigationState", snapshot.moduleKey)
        assertEquals("workspace/project/files", snapshot.currentPath)
        assertEquals(3, snapshot.backStack.size)
        assertEquals(listOf("root", "workspace", "project"), snapshot.graphChain)
        assertEquals(listOf("workspace/project"), snapshot.modalContextPaths)
        assertFalse(snapshot.isBootstrapping)
    }

    @Test
    fun `an entry knows its own route and enclosing graphs`() {
        val top = parseNavigationState(stateJson)!!.backStack.last()
        assertEquals("files", top.route)
        assertEquals(listOf("workspace", "project"), top.graphChain)
        assertEquals(mapOf("id" to "7"), top.params)
    }

    @Test
    fun `a top level entry has no enclosing graphs`() {
        val bottom = parseNavigationState(stateJson)!!.backStack.first()
        assertEquals("home", bottom.route)
        assertTrue(bottom.graphChain.isEmpty())
    }

    @Test
    fun `a store without navigation produces no snapshot`() {
        assertNull(parseNavigationState("""{"com.example.AuthState":{"isAuthenticated":true}}"""))
    }

    @Test
    fun `unparseable state does not throw`() {
        assertNull(parseNavigationState("{invalid json!!!"))
    }

    private fun start(cls: String, name: String, target: String, callId: String, at: Long) =
        LogicMethodStart(
            logicClass = cls,
            methodName = name,
            params = mapOf("target" to target),
            callId = callId,
            timestampMs = at
        )

    @Test
    fun `pairs navigation and guard spans with their verdicts`() {
        val log = buildNavigationLog(
            starts = listOf(
                start(NAVIGATION_TRACE_CLASS, "navigate", "workspace/home", "n1", 100),
                start(GUARD_TRACE_CLASS, "guard(workspace)", "workspace/home", "g1", 110),
                start("SomeAppLogic", "unrelated", "x", "u1", 120)
            ),
            completions = listOf(
                LogicMethodCompleted("g1", "Redirected(login)", "GuardResult", 12, 122),
                LogicMethodCompleted("n1", "Redirected(login)", "NavigationOutcome", 30, 130)
            )
        )

        assertEquals(2, log.size, "unrelated logic spans must not appear in the navigation log")
        assertEquals("navigate", log[0].name)
        assertEquals("workspace/home", log[0].target)
        assertFalse(log[0].isGuard)
        assertTrue(log[1].isGuard)
        assertTrue(log.all { it.diverted }, "a redirect diverted the navigation")
    }

    @Test
    fun `an in flight navigation appears with no outcome rather than vanishing`() {
        val log = buildNavigationLog(
            starts = listOf(start(GUARD_TRACE_CLASS, "guard(auth)", "auth/home", "g1", 100)),
            completions = emptyList()
        )

        assertEquals(1, log.size)
        assertNull(log[0].outcome)
        assertNull(log[0].durationMs)
        assertFalse(log[0].diverted, "an unfinished guard has not diverted anything yet")
    }

    @Test
    fun `an allowed navigation is not marked diverted`() {
        val log = buildNavigationLog(
            starts = listOf(start(GUARD_TRACE_CLASS, "guard(workspace)", "workspace/home", "g1", 100)),
            completions = listOf(LogicMethodCompleted("g1", "Allow", "GuardResult", 3, 103))
        )
        assertFalse(log.single().diverted)
    }
}
