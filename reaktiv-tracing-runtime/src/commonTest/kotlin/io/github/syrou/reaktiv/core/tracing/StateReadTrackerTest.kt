package io.github.syrou.reaktiv.core

import io.github.syrou.reaktiv.core.tracing.StateRead
import io.github.syrou.reaktiv.core.tracing.StateReadTracker
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StateReadTrackerTest {

    @AfterTest
    fun tearDown() {
        StateReadTracker.clearObservers()
        StateReadTracker.reset()
    }

    @Test
    fun duplicateReadsAreRecordedOnce() {
        StateReadTracker.notifyStateRead("com.example.CounterState", "com.example.CounterScreen")
        StateReadTracker.notifyStateRead("com.example.CounterState", "com.example.CounterScreen")
        StateReadTracker.notifyStateRead("com.example.CounterState", "com.example.OtherScreen")

        assertEquals(2, StateReadTracker.snapshot().size)
    }

    @Test
    fun readsAccrueWhileNoObserverIsRegistered() {
        StateReadTracker.notifyStateRead("com.example.AuthState", "com.example.LoginScreen")

        assertEquals(
            setOf(StateRead("com.example.AuthState", "com.example.LoginScreen")),
            StateReadTracker.snapshot()
        )
    }

    @Test
    fun lateObserverReceivesReplayOfSeenReads() {
        StateReadTracker.notifyStateRead("com.example.AuthState", "com.example.LoginScreen")
        StateReadTracker.notifyStateRead("com.example.NavState", "com.example.MainRender")

        val received = mutableListOf<StateRead>()
        StateReadTracker.addObserver { received.add(it) }

        assertEquals(2, received.size)
        assertTrue(received.contains(StateRead("com.example.AuthState", "com.example.LoginScreen")))
        assertTrue(received.contains(StateRead("com.example.NavState", "com.example.MainRender")))
    }

    @Test
    fun observerReceivesOnlyNewUniqueReads() {
        val received = mutableListOf<StateRead>()
        StateReadTracker.addObserver { received.add(it) }

        StateReadTracker.notifyStateRead("com.example.CounterState", "com.example.CounterScreen")
        StateReadTracker.notifyStateRead("com.example.CounterState", "com.example.CounterScreen")

        assertEquals(1, received.size)
    }

    @Test
    fun resetClearsTheRegistry() {
        StateReadTracker.notifyStateRead("com.example.CounterState", "com.example.CounterScreen")
        StateReadTracker.reset()

        assertEquals(0, StateReadTracker.snapshot().size)
    }
}
