import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.extension.openLink
import io.github.syrou.reaktiv.navigation.model.GuardResult
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
}
