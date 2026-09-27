package io.github.syrou.reaktiv.devtools.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun page(protocol: String, search: String, storedUrl: String?): JsAny = js("""({
    location: { protocol: protocol, search: search },
    sessionStorage: {
        items: storedUrl === null ? {} : { 'reaktiv.devtools.serverUrl': storedUrl },
        getItem: function(key) { return Object.prototype.hasOwnProperty.call(this.items, key) ? this.items[key] : null; },
        setItem: function(key, value) { this.items[key] = value; },
        removeItem: function(key) { delete this.items[key]; }
    }
})""")

private fun storedUrl(win: JsAny): String? = js("win.sessionStorage.getItem('reaktiv.devtools.serverUrl')")

class DevToolsPageAddressTest {

    @Test
    fun `a host and port in the page address becomes a websocket url`() {
        val win = page("http:", "?devtools=localhost:8090", null)

        assertEquals("ws://localhost:8090/ws", readServerUrl(win, "devtools"))
        assertEquals("ws://localhost:8090/ws", storedUrl(win))
    }

    @Test
    fun `a secure page connects over a secure websocket`() {
        assertEquals("wss://tools.example.com/ws", readServerUrl(page("https:", "?devtools=tools.example.com", null), "devtools"))
    }

    @Test
    fun `a full url is used as given`() {
        val win = page("http:", "?devtools=ws%3A%2F%2F192.168.1.20%3A9000%2Fsocket", null)

        assertEquals("ws://192.168.1.20:9000/socket", readServerUrl(win, "devtools"))
    }

    @Test
    fun `a reload without the parameter keeps the server the tab was given`() {
        assertEquals(
            "ws://localhost:8090/ws",
            readServerUrl(page("http:", "", "ws://localhost:8090/ws"), "devtools")
        )
    }

    @Test
    fun `an empty parameter forgets the server`() {
        val win = page("http:", "?devtools=", "ws://localhost:8090/ws")

        assertNull(readServerUrl(win, "devtools"))
        assertNull(storedUrl(win))
    }

    @Test
    fun `no parameter and nothing stored means no server`() {
        assertNull(readServerUrl(page("http:", "?other=1", null), "devtools"))
    }
}
