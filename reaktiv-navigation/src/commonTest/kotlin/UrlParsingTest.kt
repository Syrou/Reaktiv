import io.github.syrou.reaktiv.navigation.util.parseUrlWithQueryParams
import kotlin.test.Test
import kotlin.test.assertEquals

class UrlParsingTest {

    @Test
    fun `test parseUrlWithQueryParams decodes basic encoded values`() {
        val url = "profile?name=John%20Doe&email=john%40example.com"
        val (cleanPath, queryParams) = parseUrlWithQueryParams(url)
        
        assertEquals("profile", cleanPath)
        assertEquals("John Doe", queryParams["name"])
        assertEquals("john@example.com", queryParams["email"])
    }

    @Test
    fun `test parseUrlWithQueryParams decodes plus signs as spaces`() {
        val url = "search?query=hello+world&category=tech+news"
        val (cleanPath, queryParams) = parseUrlWithQueryParams(url)
        
        assertEquals("search", cleanPath)
        assertEquals("hello world", queryParams["query"])
        assertEquals("tech news", queryParams["category"])
    }

    @Test
    fun `test parseUrlWithQueryParams decodes special characters`() {
        val url = "profile?data=%7B%22key%22%3A%22value%22%7D&symbol=%26%23%40%24"
        val (cleanPath, queryParams) = parseUrlWithQueryParams(url)
        
        assertEquals("profile", cleanPath)
        assertEquals("{\"key\":\"value\"}", queryParams["data"])
        assertEquals("&#@$", queryParams["symbol"])
    }

    @Test
    fun `test parseUrlWithQueryParams handles unicode characters`() {
        val url = "profile?name=%E2%9C%93%20Test&emoji=%F0%9F%98%80"
        val (cleanPath, queryParams) = parseUrlWithQueryParams(url)
        
        assertEquals("profile", cleanPath)
        assertEquals("✓ Test", queryParams["name"])
        assertEquals("😀", queryParams["emoji"])
    }

    @Test
    fun `test parseUrlWithQueryParams handles mixed encoded and unencoded values`() {
        val url = "profile?encoded=hello%20world&normal=simple&mixed=test%2Bvalue"
        val (cleanPath, queryParams) = parseUrlWithQueryParams(url)
        
        assertEquals("profile", cleanPath)
        assertEquals("hello world", queryParams["encoded"])
        assertEquals("simple", queryParams["normal"])
        assertEquals("test+value", queryParams["mixed"])
    }

    @Test
    fun `test parseUrlWithQueryParams handles empty and malformed parameters`() {
        val url = "profile?empty=&standalone&encoded=%20&normal=test"
        val (cleanPath, queryParams) = parseUrlWithQueryParams(url)
        
        assertEquals("profile", cleanPath)
        assertEquals("", queryParams["empty"])
        assertEquals("true", queryParams["standalone"]) // Parameters without values become "true"
        assertEquals(" ", queryParams["encoded"]) // Encoded space
        assertEquals("test", queryParams["normal"])
    }

    @Test
    fun `test parseUrlWithQueryParams handles URL without query parameters`() {
        val url = "profile/settings"
        val (cleanPath, queryParams) = parseUrlWithQueryParams(url)
        
        assertEquals("profile/settings", cleanPath)
        assertEquals(emptyMap<String, String>(), queryParams)
    }

    @Test
    fun `test parseUrlWithQueryParams handles complex deeplink scenario`() {
        val url = "app://myapp/profile?user_id=123&name=Jane%20Smith&email=jane%2Bsmith%40example.com&data=%7B%22theme%22%3A%22dark%22%2C%22lang%22%3A%22en%22%7D"
        val (cleanPath, queryParams) = parseUrlWithQueryParams(url)
        
        assertEquals("app://myapp/profile", cleanPath)
        assertEquals("123", queryParams["user_id"])
        assertEquals("Jane Smith", queryParams["name"])
        assertEquals("jane+smith@example.com", queryParams["email"])
        assertEquals("{\"theme\":\"dark\",\"lang\":\"en\"}", queryParams["data"])
    }

    @Test
    fun `test parseUrlWithQueryParams removes trailing slash from path`() {
        val url = "profile/?name=test&value=123"
        val (cleanPath, queryParams) = parseUrlWithQueryParams(url)
        
        assertEquals("profile", cleanPath)
        assertEquals("test", queryParams["name"])
        assertEquals("123", queryParams["value"])
    }
}