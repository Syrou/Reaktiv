import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InterceptDeclarationTest {

    private fun screen(name: String) = object : Screen {
        override val route = name
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(name) }
    }

    @Test
    fun `a start declared inside intercept is rejected instead of silently dropped`() {
        val home = screen("home")
        val failure = assertFailsWith<IllegalStateException> {
            createNavigationModule {
                rootGraph {
                    intercept(guard = { GuardResult.Allow }) {
                        start(home)
                        screens(home)
                    }
                }
            }
        }
        assertTrue("intercept" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a layout declared inside intercept is rejected instead of silently dropped`() {
        val home = screen("home")
        assertFailsWith<IllegalStateException> {
            createNavigationModule {
                rootGraph {
                    start(home)
                    intercept(guard = { GuardResult.Allow }) {
                        layout { content -> content() }
                        screens(screen("inner"))
                    }
                }
            }
        }
    }
}
