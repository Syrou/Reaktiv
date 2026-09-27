import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class ParamsRoundTripTest {

    @Serializable
    data class Profile(val id: Long, val score: Double, val tags: List<String>)

    private fun roundTrip(params: Params): Params =
        Json.decodeFromString(Params.serializer(), Json.encodeToString(Params.serializer(), params))

    @Test
    fun `numbers restore with their value and read back through typed getters`() {
        val restored = roundTrip(
            Params.of("lat" to 59.334591, "ts" to 5L, "big" to 9_000_000_000L, "ratio" to 1.5f, "count" to 3)
        )

        assertEquals(59.334591, restored.getDouble("lat"))
        assertEquals(59.334591, restored.getTyped<Double>("lat"))
        assertEquals(5L, restored.getLong("ts"))
        assertEquals(5L, restored.getTyped<Long>("ts"))
        assertEquals(9_000_000_000L, restored.getTyped<Long>("big"))
        assertEquals(1.5f, restored.getTyped<Float>("ratio"))
        assertEquals(3, restored.getTyped<Int>("count"))
    }

    @Test
    fun `typed objects restore as their type`() {
        val profile = Profile(7L, 0.25, listOf("a", "b"))

        val restored = roundTrip(
            Params.empty().withTyped("profile", profile).withTyped("tags", listOf("x", "y"))
        )

        assertEquals(profile, restored.getTyped<Profile>("profile"))
        assertEquals(listOf("x", "y"), restored.getTyped<List<String>>("tags"))
    }

    @Test
    fun `a typed object reads as its JSON text`() {
        val params = Params.empty().withTyped("tags", listOf("x", "y"))

        assertEquals("""["x","y"]""", params.getString("tags"))
        assertEquals("""["x","y"]""", roundTrip(params).getString("tags"))
    }

    @Test
    fun `a null inside a list restores as null`() {
        val restored = roundTrip(Params.of("list" to listOf("a", null)))

        assertEquals(listOf("a", null), restored["list"])
    }

    @Test
    fun `encoding a restored Params gives the same JSON as the original`() {
        val params = Params.of("lat" to 59.334591, "ts" to 5L, "name" to "Ada")
            .withTyped("profile", Profile(7L, 0.25, listOf("a")))

        val original = Json.encodeToString(Params.serializer(), params)
        val reEncoded = Json.encodeToString(Params.serializer(), roundTrip(params))

        assertEquals(original, reEncoded)
    }

    @Test
    fun `identical typed params are equal whatever serializer instance built them`() {
        val first = Params.empty().withTyped("ids", listOf(1, 2), ListSerializer(Int.serializer()))
        val second = Params.empty().withTyped("ids", listOf(1, 2), ListSerializer(Int.serializer()))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `a primitive put in the navigation builder fills its path parameter`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(
                    createNavigationModule {
                        rootGraph {
                            start(SplashScreen)
                            screens(StatsScreen)
                        }
                    }
                )
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.navigation {
                navigateTo("stats/{type}") { put("type", "weekly") }
            }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("weekly", state.currentEntry.params["type"])
            assertEquals("stats/weekly", state.currentFullPath)
        }
}
