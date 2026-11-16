import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.TraverseDirection
import io.github.syrou.reaktiv.navigation.TraversePresentation
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.history.ExternalLocation
import io.github.syrou.reaktiv.navigation.history.ExternalOutcome
import io.github.syrou.reaktiv.navigation.history.LocationSnapshot
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.model.NavigationGuard
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenGuardRestoreTest {

    private val calls = mutableListOf<String?>()
    private var allowed = true

    private val home = historyScreen("home")
    private val upsell = historyScreen("upsell")
    private val other = historyScreen("other")
    private val item = object : Screen {
        override val route = "item/{id}"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val guard: NavigationGuard = { _ ->
            calls += params.getString("id")
            if (allowed) GuardResult.Allow else GuardResult.RedirectTo(upsell)
        }

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private fun TestScope.store() = createStore {
        module(
            createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, upsell, other, item)
                }
            }
        )
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private class Captured(val snapshot: LocationSnapshot, val url: String)

    private suspend fun Store.logic() = selectLogic<NavigationLogic>()

    private suspend fun Store.state() = selectState<NavigationState>().first()

    private suspend fun Store.locations() = state().backStack.map { it.location }

    private suspend fun Store.capture(): Captured {
        val stack = state().backStack
        return Captured(logic().locationCodec.snapshotOf(stack), stack.last().location)
    }

    private suspend fun Store.restore(
        captured: Captured,
        direction: TraverseDirection = TraverseDirection.Forward
    ): ExternalOutcome = logic().applyExternalLocation(
        ExternalLocation.Snapshot(
            captured.snapshot,
            ExternalLocation.Url(captured.url),
            direction,
            TraversePresentation.Animate
        )
    )

    @Test
    fun `restoring history runs the guard of a screen it brings back with that entry's params`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            store.navigation { navigateTo("item/7") }
            advanceUntilIdle()
            val deep = store.capture()
            store.navigation { popUpTo("home") }
            advanceUntilIdle()
            calls.clear()

            val outcome = store.restore(deep)
            advanceUntilIdle()

            assertEquals(ExternalOutcome.Landed, outcome)
            assertEquals(listOf("home", "item/7"), store.locations())
            assertEquals(listOf<String?>("7"), calls)
        }

    @Test
    fun `restoring history redirects when a screen's guard no longer allows it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            store.navigation { navigateTo("item/7") }
            advanceUntilIdle()
            val deep = store.capture()
            store.navigation { popUpTo("home") }
            advanceUntilIdle()
            allowed = false

            val outcome = store.restore(deep)
            advanceUntilIdle()

            assertEquals(ExternalOutcome.Redirected("upsell"), outcome)
            assertEquals(listOf("home", "upsell"), store.locations())
        }

    @Test
    fun `a screen guard beneath the restored top still decides`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            store.navigation { navigateTo("item/7") }
            store.navigation { navigateTo("other") }
            advanceUntilIdle()
            val withItemBeneath = store.capture()
            store.navigation { popUpTo("home") }
            advanceUntilIdle()
            allowed = false

            val outcome = store.restore(withItemBeneath)
            advanceUntilIdle()

            assertEquals(ExternalOutcome.Redirected("upsell"), outcome)
            assertEquals(listOf("home", "upsell"), store.locations())
        }

    @Test
    fun `going back in history to a guarded screen does not ask its guard again`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            store.navigation { navigateTo("item/7") }
            advanceUntilIdle()
            val atItem = store.capture()
            store.navigation { navigateTo("other") }
            advanceUntilIdle()
            calls.clear()
            allowed = false

            val outcome = store.restore(atItem, TraverseDirection.Back)
            advanceUntilIdle()

            assertEquals(ExternalOutcome.Landed, outcome)
            assertEquals(listOf("home", "item/7"), store.locations())
            assertEquals(emptyList(), calls)
        }
}
