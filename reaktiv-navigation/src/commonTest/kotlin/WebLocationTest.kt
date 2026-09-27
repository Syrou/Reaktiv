import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.history.UrlStyle
import io.github.syrou.reaktiv.navigation.history.WebLocation
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebLocationTest {

    private val userScreen = object : Screen {
        override val route = "user/{id}"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private fun user(vararg params: Pair<String, Any>) =
        NavigationEntry(userScreen, "home/user/{id}", Params.of(*params))

    @Test
    fun `a hash location carries the encoded path and the simple params that are not secrets`() {
        val location = WebLocation(UrlStyle.Hash, "/")

        val url = location.format(user("id" to "a b/c", "q" to "x y", "page" to 2, "token" to "secret"))

        assertEquals("#/home/user/a%20b%2Fc?page=2&q=x%20y", url)
    }

    @Test
    fun `hidden keys and objects stay out of the url`() {
        val location = WebLocation(UrlStyle.Hash, "/")

        val url = location.format(user("id" to "1", "draft" to "long text", "tags" to listOf("a")), setOf("draft"))

        assertEquals("#/home/user/1", url)
    }

    @Test
    fun `a path location lives under the base path`() {
        val location = WebLocation(UrlStyle.Path, "/app/")

        assertEquals("/app/home/user/42", location.format(user("id" to "42")))
    }

    @Test
    fun `a hash is parsed only when it is route shaped`() {
        val location = WebLocation(UrlStyle.Hash, "/")

        assertEquals("home/user/a%20b%2Fc?q=x", location.parse("/", "", "#/home/user/a%20b%2Fc?q=x"))
        assertEquals("", location.parse("/", "", ""))
        assertEquals("", location.parse("/", "", "#/"))
        assertNull(location.parse("/", "", "#access_token=abc"))
    }

    @Test
    fun `a path is parsed relative to the base and outside paths are not ours`() {
        val location = WebLocation(UrlStyle.Path, "/app/index.html")

        assertEquals("home/user/42?q=1", location.parse("/app/home/user/42", "?q=1", ""))
        assertEquals("", location.parse("/app/index.html", "", ""))
        assertEquals("", location.parse("/app", "", ""))
        assertNull(location.parse("/other/page", "", ""))
    }
}
