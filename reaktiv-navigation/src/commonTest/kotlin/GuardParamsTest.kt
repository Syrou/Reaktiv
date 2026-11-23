import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.extension.openLink
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
class GuardParamsTest {

    private val home = historyScreen("home")
    private val projects = historyScreen("projects")
    private val project = historyScreen("project")
    private val evaluations = mutableListOf<String?>()
    private val items = historyScreen("items")
    private val item = guardedScreen("items/{item-id}") { _ ->
        val itemId = params.getString("item-id")
        evaluations.add("screen:$itemId")
        if (itemId == "known") GuardResult.Allow else GuardResult.RedirectTo(items)
    }

    private fun guardedScreen(route: String, guard: NavigationGuard): Screen = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val guard: NavigationGuard = guard

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private fun TestScope.store(cached: Boolean = false) = createStore {
        module(
            createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, projects)
                    intercept(
                        guard = { _ ->
                            val projectId = params.getString("projectId")
                            evaluations.add(projectId)
                            if (projectId == "own") GuardResult.Allow else GuardResult.RedirectTo(projects)
                        },
                        cacheKey = if (cached) { _ -> "signed-in" } else null
                    ) {
                        screens(project)
                    }
                }
            }
        )
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private fun TestScope.itemStore() = createStore {
        module(
            createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, items)
                    intercept(
                        guard = { _ ->
                            evaluations.add("zone:${params.getString("item-id")}")
                            GuardResult.Allow
                        }
                    ) {
                        screens(item)
                    }
                }
            }
        )
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private suspend fun Store.current() =
        selectState<NavigationState>().first().currentEntry

    @Test
    fun `a guard reads the params of a navigation inside the app`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()

            store.navigation { navigateTo("project?projectId=own") }
            advanceUntilIdle()
            assertEquals("project", store.current().route)

            store.navigateBack()
            advanceUntilIdle()
            store.navigation { navigateTo("project?projectId=other") }
            advanceUntilIdle()
            assertEquals("projects", store.current().route)
        }

    @Test
    fun `a guard reads the params of a link the same way`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()

            store.openLink("project?projectId=other")
            advanceUntilIdle()
            assertEquals("projects", store.current().route)

            store.openLink("project?projectId=own")
            advanceUntilIdle()
            assertEquals("project", store.current().route)
            assertEquals(listOf<String?>("other", "own"), evaluations)
        }

    @Test
    fun `a cached guard answers again for other params`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store(cached = true)
            advanceUntilIdle()

            store.navigation { navigateTo("project?projectId=own") }
            advanceUntilIdle()
            store.navigateBack()
            advanceUntilIdle()
            store.navigation { navigateTo("project?projectId=own") }
            advanceUntilIdle()
            assertEquals("project", store.current().route)
            assertEquals(listOf<String?>("own"), evaluations)

            store.navigateBack()
            advanceUntilIdle()
            store.navigation { navigateTo("project?projectId=other") }
            advanceUntilIdle()
            assertEquals("projects", store.current().route)
            assertEquals(listOf<String?>("own", "other"), evaluations)
        }

    @Test
    fun `guards read the path params of a link like its screen does`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = itemStore()
            advanceUntilIdle()

            store.openLink("items/other")
            advanceUntilIdle()
            assertEquals("items", store.current().route)

            store.openLink("items/known")
            advanceUntilIdle()
            assertEquals("items/known", store.current().location)
            assertEquals("known", store.current().params.getString("item-id"))
            assertEquals(listOf<String?>("zone:other", "screen:other", "zone:known", "screen:known"), evaluations)
        }
}
