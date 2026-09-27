import io.github.syrou.reaktiv.navigation.param.Params
import kotlin.test.Test
import kotlin.test.assertEquals

class ParamsScalarTextTest {

    private data class Point(val x: Int, val y: Int)

    @Test
    fun `a value that is not a string reads back as its own text`() {
        val params = Params.of("point" to Point(1, 2), "ratio" to 1.5, "count" to 3, "on" to true)

        assertEquals("Point(x=1, y=2)", params.getString("point"))
        assertEquals("1.5", params.getString("ratio"))
        assertEquals("3", params.getString("count"))
        assertEquals("true", params.getString("on"))
    }
}
