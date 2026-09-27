package io.github.syrou.reaktiv.introspection

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun fakeDownloadWindow(): JsAny = js("""({
    clicks: [],
    blobs: [],
    revokeScheduled: 0,
    attached: 0,
    setTimeout: function(fn, ms) { this.revokeScheduled++; },
    URL: {
        createObjectURL: function(blob) { this.owner.blobs.push(blob); return 'blob:reaktiv/' + this.owner.blobs.length; },
        revokeObjectURL: function(url) {}
    },
    document: {
        body: {
            appendChild: function(node) { this.owner.attached++; },
            removeChild: function(node) { this.owner.attached--; }
        },
        createElement: function(tag) {
            var owner = this.owner;
            return {
                tag: tag,
                style: {},
                click: function() { owner.clicks.push({ tag: this.tag, href: this.href, download: this.download }); }
            };
        }
    },
    init: function() {
        this.URL.owner = this;
        this.document.owner = this;
        this.document.body.owner = this;
        return this;
    }
}).init()""")

private fun clickCount(win: JsAny): Int = js("win.clicks.length")

private fun clickedDownloadName(win: JsAny): String = js("win.clicks[0].download")

private fun clickedTag(win: JsAny): String = js("win.clicks[0].tag")

private fun blobSize(win: JsAny): Int = js("win.blobs[0].size")

private fun blobType(win: JsAny): String = js("win.blobs[0].type")

private fun attachedAnchors(win: JsAny): Int = js("win.attached")

private fun revokesScheduled(win: JsAny): Int = js("win.revokeScheduled")

private fun windowWithoutDocument(): JsAny = js("({})")

class BrowserSessionExportTest {

    @Test
    fun `a session download clicks an anchor that carries the file bytes`() = runTest {
        val win = fakeDownloadWindow()
        val bytes = gzipCompress(sessionLikeJson(20).encodeToByteArray())

        val saved = SessionFileExport(PlatformContext(win)).saveToDownloads(bytes, "reaktiv_session.json.gz")

        assertEquals("reaktiv_session.json.gz", saved)
        assertEquals(1, clickCount(win))
        assertEquals("a", clickedTag(win))
        assertEquals("reaktiv_session.json.gz", clickedDownloadName(win))
        assertEquals(bytes.size, blobSize(win))
        assertEquals("application/gzip", blobType(win))
        assertEquals(0, attachedAnchors(win))
        assertEquals(1, revokesScheduled(win))
    }

    @Test
    fun `a plain json download is typed as json`() {
        val win = fakeDownloadWindow()

        SessionFileExport(PlatformContext(win)).saveToDownloads("{}".encodeToByteArray(), "session.json")

        assertEquals("application/json", blobType(win))
        assertEquals(2, blobSize(win))
    }

    @Test
    fun `a file without an extension is not typed as json so the browser keeps its name`() {
        val win = fakeDownloadWindow()

        SessionFileExport(PlatformContext(win)).saveToDownloads("{}".encodeToByteArray(), "apple-app-site-association")

        assertEquals("apple-app-site-association", clickedDownloadName(win))
        assertEquals("application/octet-stream", blobType(win))
    }

    @Test
    fun `an xml download is typed as xml`() {
        val win = fakeDownloadWindow()

        SessionFileExport(PlatformContext(win)).saveToDownloads("<a/>".encodeToByteArray(), "AndroidManifest-app-links.xml")

        assertEquals("application/xml", blobType(win))
    }

    @Test
    fun `downloading without a document reports failure instead of pretending`() {
        val export = SessionFileExport(PlatformContext(windowWithoutDocument()))

        val failure = assertFailsWith<UnsupportedOperationException> {
            export.saveToDownloads(ByteArray(3), "session.json.gz")
        }

        assertTrue(failure.message.orEmpty().contains("session.json.gz"))
    }
}
