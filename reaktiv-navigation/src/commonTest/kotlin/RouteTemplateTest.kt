import io.github.syrou.reaktiv.navigation.util.RouteTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteTemplateTest {

    @Test
    fun `a param segment captures one non empty segment`() {
        val template = RouteTemplate.parse("users/{id}")

        assertEquals(mapOf("id" to "42"), template.match("users/42"))
        assertNull(template.match("users/42/edit"))
        assertNull(template.match("users"))
        assertNull(template.match("members/42"))
    }

    @Test
    fun `captured values are decoded once with path rules`() {
        val template = RouteTemplate.parse("users/{id}")

        assertEquals("John Doe", template.match("users/John%20Doe")?.get("id"))
        assertEquals("a/b", template.match("users/a%2Fb")?.get("id"))
        assertEquals("c+c", template.match("users/c+c")?.get("id"))
        assertEquals("Åsa", template.match("users/%C3%85sa")?.get("id"))
        assertEquals("Åsa", template.match("users/Åsa")?.get("id"))
        assertEquals("%41", template.match("users/%2541")?.get("id"))
    }

    @Test
    fun `a template that starts with a placeholder matches`() {
        assertEquals(mapOf("slug" to "about"), RouteTemplate.parse("{slug}").match("about"))
        assertEquals(mapOf("lang" to "en"), RouteTemplate.parse("{lang}/home").match("en/home"))
    }

    @Test
    fun `a mixed segment captures around its literal text`() {
        assertEquals(mapOf("id" to "7"), RouteTemplate.parse("item-{id}").match("item-7"))
        assertNull(RouteTemplate.parse("item-{id}").match("thing-7"))
    }

    @Test
    fun `a scheme and host pattern matches an absolute link`() {
        val template = RouteTemplate.parse("{scheme}://{host}/invitations/{type}/confirm/{token}")

        val values = template.match("https://staging.example.com/invitations/team/confirm/abc.def")

        assertEquals(
            mapOf("scheme" to "https", "host" to "staging.example.com", "type" to "team", "token" to "abc.def"),
            values
        )
    }

    @Test
    fun `param names come from every segment of the template`() {
        assertEquals(listOf("orgId", "projectId"), RouteTemplate.parse("orgs/{orgId}/projects/{projectId}").paramNames)
    }

    @Test
    fun `building encodes values so they match back to the same values`() {
        val template = RouteTemplate.parse("users/{id}")
        val tricky = listOf("a/b", "50%", "c+c", "Åsa 🎉", "x?y#z", "plain")

        for (value in tricky) {
            val built = (template.fill { value } as RouteTemplate.Fill.Filled).location
            assertEquals(mapOf("id" to value), template.match(built!!), "round trip of '$value' through '$built'")
        }
        assertEquals("users/a%2Fb%20c", (template.fill { "a/b c" } as RouteTemplate.Fill.Filled).location)
    }

    @Test
    fun `building with a missing value reports it instead of guessing`() {
        val template = RouteTemplate.parse("company/{companyId}/user/{userId}")
        val values = mapOf("companyId" to "acme")

        assertEquals(RouteTemplate.Fill.Missing(listOf("userId")), template.fill(values::get))
        assertEquals(listOf("userId"), template.missing(values::get))
        assertEquals("company/acme/user/{userId}", template.render(values::get, encoded = false))
    }

    @Test
    fun `a static segment outranks a param in the same position`() {
        val sorted = listOf("{lang}/home", "en/{page}", "users/{id}/{tab}", "users/{id}/edit")
            .map(RouteTemplate::parse)
            .sortedWith(RouteTemplate.specificity)
            .map { it.template }

        assertTrue(sorted.indexOf("en/{page}") < sorted.indexOf("{lang}/home"))
        assertTrue(sorted.indexOf("users/{id}/edit") < sorted.indexOf("users/{id}/{tab}"))
    }

    @Test
    fun `templates that differ only in param names have the same shape`() {
        assertTrue(RouteTemplate.parse("user/{id}").sameShapeAs(RouteTemplate.parse("user/{uid}")))
        assertFalse(RouteTemplate.parse("user/{id}").sameShapeAs(RouteTemplate.parse("users/{id}")))
    }

    @Test
    fun `parsing the same template twice reuses the parsed template`() {
        assertTrue(RouteTemplate.parse("orders/{orderId}/items") === RouteTemplate.parse("orders/{orderId}/items"))
    }

}
