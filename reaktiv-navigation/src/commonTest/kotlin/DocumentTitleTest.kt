import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.history.UrlStyle
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentTitleTest {

    private val home = historyScreen("home")
    private val a = historyScreen("a")
    private val sheet = historyModal("sheet")
    private val alert = historyModal("alert", RenderLayer.SYSTEM)

    @Test
    fun `the title follows the entry the address names and skips system entries`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = createStore {
                module(
                    createNavigationModule {
                        browserHistoryForTesting(browser, UrlStyle.Hash)
                        rootGraph {
                            start(home)
                            screens(home, a)
                            modals(sheet, alert)
                        }
                    }
                )
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            store.navigation { navigateTo("a") }
            store.navigation { navigateTo(sheet) }
            store.navigation { navigateTo(alert) }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals(sheet, state.titledEntry?.navigatable)
        }

    @Test
    fun `a placeholder alone has no title`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val browser = fakeBrowser()
            val store = createStore {
                module(
                    createNavigationModule {
                        browserHistoryForTesting(browser, UrlStyle.Hash)
                        rootGraph {
                            start(home)
                            screens(home)
                        }
                    }
                )
                coroutineContext(StandardTestDispatcher(testScheduler))
            }

            assertNull(store.selectState<NavigationState>().first().titledEntry)
        }
}
