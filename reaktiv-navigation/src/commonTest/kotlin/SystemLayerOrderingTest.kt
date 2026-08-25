import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.param.Params
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemLayerOrderingTest {

    private object Loader : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) = Unit
    }

    private object Alert : Modal {
        override val route = "system-alert"
        override val enterTransition = NavTransition.Fade
        override val exitTransition = NavTransition.FadeOut
        override val renderLayer = RenderLayer.SYSTEM

        @Composable
        override fun Content(params: Params) = Unit
    }

    @Test
    fun a_system_modal_outranks_the_loading_overlay() {
        assertTrue(
            Alert.elevation > Loader.elevation,
            "a system alert must be able to sit above the evaluation overlay, " +
                "alert=${Alert.elevation} loader=${Loader.elevation}"
        )
    }

    @Test
    fun the_loading_overlay_sits_at_the_bottom_of_the_system_layer() {
        assertEquals(
            0f,
            Loader.elevation,
            "the loader is the floor of the system layer so anything else can be placed above it"
        )
    }

    @Test
    fun a_system_modal_declares_the_system_layer() {
        assertEquals(RenderLayer.SYSTEM, Alert.renderLayer)
        assertEquals(RenderLayer.SYSTEM, Loader.renderLayer)
    }
}
