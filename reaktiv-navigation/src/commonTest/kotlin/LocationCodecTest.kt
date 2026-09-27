import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.history.LocationCodec
import io.github.syrou.reaktiv.navigation.history.LocationSnapshot
import io.github.syrou.reaktiv.navigation.history.SnapshotEntry
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class LocationCodecTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private fun modal(route: String, layer: RenderLayer = RenderLayer.GLOBAL_OVERLAY) = object : Modal {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val renderLayer = layer

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private val loading = object : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("loading")
        }
    }

    private val home = screen("home")
    private val user = screen("user/{id}")
    private val note = modal("note")
    private val alert = modal("alert", RenderLayer.SYSTEM)

    private val module = createNavigationModule {
        loadingModal(loading)
        rootGraph {
            start(home)
            screens(home, user)
            modals(note, alert)
        }
    }

    private val codec = LocationCodec(module.precomputedData, Json)

    private fun entry(navigatable: Navigatable, path: String, vararg params: Pair<String, Any>) =
        NavigationEntry(navigatable, path, Params.of(*params))

    @Test
    fun `a snapshot keeps screens and modals but not system entries or the loading modal`() {
        val snapshot = codec.snapshotOf(
            listOf(
                entry(home, "home"),
                entry(user, "user/{id}", "id" to "42"),
                entry(note, "note"),
                entry(alert, "alert"),
                entry(loading, "loading")
            )
        )

        assertEquals(listOf("home", "user/{id}", "note"), snapshot.entries.map { it.path })
    }

    @Test
    fun `the encoding does not depend on param order or on whole number types`() {
        val first = codec.snapshotOf(listOf(entry(user, "user/{id}", "id" to "1", "a" to 5L, "b" to 2)))
        val second = codec.snapshotOf(listOf(entry(user, "user/{id}", "b" to 2, "id" to "1", "a" to 5)))

        assertEquals(codec.encode(first), codec.encode(second))
        assertEquals(codec.digest(codec.encode(first)), codec.digest(codec.encode(second)))
    }

    @Test
    fun `sensitive params never enter a snapshot`() {
        val snapshot = codec.snapshotOf(listOf(entry(user, "user/{id}", "id" to "42", "accessToken" to "abc")))

        assertEquals(setOf("id"), snapshot.entries.single().params.keys)
    }

    @Test
    fun `a snapshot decodes back to itself and anything else decodes to nothing`() {
        val snapshot = codec.snapshotOf(listOf(entry(home, "home"), entry(user, "user/{id}", "id" to "42")))

        assertEquals(snapshot, codec.decode(codec.encode(snapshot)))
        assertNull(codec.decode("not json"))
        assertNull(codec.decode("""{"v":99,"e":[]}"""))
        assertNull(codec.decode("""{"v":1}"""))
    }

    @Test
    fun `restoring reuses live entries that match and decodes the rest`() {
        val live = listOf(entry(home, "home"), entry(user, "user/{id}", "id" to "42"))
        val target = codec.snapshotOf(live + entry(user, "user/{id}", "id" to "43"))

        val restored = codec.entriesOf(target, live)!!

        assertSame(live[0], restored[0])
        assertSame(live[1], restored[1])
        assertEquals("43", restored[2].params.getString("id"))
        assertEquals("user/43", restored[2].location)
    }

    @Test
    fun `a snapshot naming a route that no longer exists cannot be restored`() {
        val stale = LocationSnapshot(listOf(SnapshotEntry("gone", JsonObject(emptyMap()))))

        assertNull(codec.entriesOf(stale, emptyList()))
    }

    @Test
    fun `different stacks have different digests`() {
        val one = codec.encode(codec.snapshotOf(listOf(entry(user, "user/{id}", "id" to "1"))))
        val two = codec.encode(codec.snapshotOf(listOf(entry(user, "user/{id}", "id" to "2"))))

        assertNotEquals(codec.digest(one), codec.digest(two))
    }
}
