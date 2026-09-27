package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserBlockedHintTest {

    private fun exchange(status: Int? = null, error: String? = null) = NetworkRequestCapture(
        id = "n1",
        startedAtMs = 0,
        durationMs = 5,
        method = "GET",
        url = "https://www.pathofexile.com/news/rss",
        responseStatus = status,
        error = error
    )

    @Test
    fun `a browser request that failed before any response explains that the browser blocked it`() {
        val hint = browserBlockedHint(exchange(error = "Fail to fetch"), platform = "Web")

        assertNotNull(hint)
        assertTrue("cross-origin" in hint)
    }

    @Test
    fun `a recorded browser session gets the same explanation`() {
        assertNotNull(browserBlockedHint(exchange(error = "Fail to fetch"), platform = "Web (Recorded)"))
    }

    @Test
    fun `a failure on a device that is not a browser gets no browser explanation`() {
        assertNull(browserBlockedHint(exchange(error = "UnknownHostException"), platform = "Android 14"))
    }

    @Test
    fun `a browser request that got a response is not explained as blocked`() {
        assertNull(browserBlockedHint(exchange(status = 500), platform = "Web"))
    }

    @Test
    fun `an unknown publisher platform gets no explanation`() {
        assertNull(browserBlockedHint(exchange(error = "Fail to fetch"), platform = null))
    }
}
