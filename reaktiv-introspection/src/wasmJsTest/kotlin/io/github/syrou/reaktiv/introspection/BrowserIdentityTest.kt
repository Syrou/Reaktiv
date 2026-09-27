package io.github.syrou.reaktiv.introspection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private fun chromeOnWindows(navigationType: String, storedId: String?): JsAny = js("""({
    navigator: {
        userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36',
        language: 'sv-SE',
        userAgentData: {
            platform: 'Windows',
            brands: [
                { brand: 'Not=A?Brand', version: '24' },
                { brand: 'Chromium', version: '140' },
                { brand: 'Google Chrome', version: '140' }
            ]
        }
    },
    performance: { getEntriesByType: function(kind) { return [{ type: navigationType }]; } },
    sessionStorage: {
        items: storedId === null ? {} : { 'reaktiv.introspection.clientId': storedId },
        getItem: function(key) { return Object.prototype.hasOwnProperty.call(this.items, key) ? this.items[key] : null; },
        setItem: function(key, value) { this.items[key] = value; }
    }
})""")

private fun firefoxOnLinux(): JsAny = js("""({
    navigator: {
        userAgent: 'Mozilla/5.0 (X11; Linux x86_64; rv:143.0) Gecko/20100101 Firefox/143.0',
        language: 'en-GB'
    }
})""")

private fun safariOnIphoneWithBlockedStorage(): JsAny = js("""({
    navigator: {
        userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.2 Mobile/15E148 Safari/604.1',
        language: 'en-US'
    },
    get sessionStorage() { throw new Error('SecurityError'); }
})""")

private fun storedClientId(win: JsAny): String? = js("win.sessionStorage.getItem('reaktiv.introspection.clientId')")

class BrowserIdentityTest {

    @Test
    fun `chromium brands name the browser and its platform`() {
        val identity = browserIdentity(chromeOnWindows("navigate", null))

        assertEquals("Chrome 140 on Windows", identity.clientName)
        assertEquals("Windows", identity.os)
        assertEquals("sv-SE", identity.locale)
    }

    @Test
    fun `the user agent names browsers without client hints`() {
        assertEquals("Firefox 143 on Linux", browserIdentity(firefoxOnLinux()).clientName)
        assertEquals("Safari 18 on iOS", browserIdentity(safariOnIphoneWithBlockedStorage()).clientName)
    }

    @Test
    fun `a reload keeps the client id the tab already had`() {
        val win = chromeOnWindows("reload", "tab-before-reload")

        assertEquals("tab-before-reload", browserIdentity(win).clientId)
    }

    @Test
    fun `a fresh load or a duplicated tab gets its own client id`() {
        val fresh = chromeOnWindows("navigate", "copied-from-another-tab")
        val duplicated = chromeOnWindows("back_forward", "copied-from-another-tab")

        val freshId = browserIdentity(fresh).clientId
        assertNotEquals("copied-from-another-tab", freshId)
        assertEquals(freshId, storedClientId(fresh))
        assertNotEquals("copied-from-another-tab", browserIdentity(duplicated).clientId)
    }

    @Test
    fun `blocked storage still yields a usable client id`() {
        val first = browserIdentity(safariOnIphoneWithBlockedStorage()).clientId
        val second = browserIdentity(safariOnIphoneWithBlockedStorage()).clientId

        assertEquals(36, first.length)
        assertNotEquals(first, second)
    }
}
