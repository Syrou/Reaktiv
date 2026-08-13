package io.github.syrou.reaktiv.devtools.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyViewTest {

    private val whole = """{"items":[1,2,3],"ok":true}"""
    private val cut = whole.take(14)

    @Test
    fun `a small json body renders as a tree straight from the preview`() {
        val view = resolveBodyView(
            preview = whole,
            load = null,
            truncated = false,
            contentType = "application/json"
        )
        assertEquals(BodyRender.TREE, view.render)
        assertEquals(whole, view.text)
        assertNull(view.note)
    }

    @Test
    fun `a truncated preview with no load yet renders raw and says why`() {
        val view = resolveBodyView(
            preview = cut,
            load = null,
            truncated = true,
            contentType = "application/json"
        )
        assertEquals(BodyRender.RAW, view.render)
        assertTrue(view.note!!.contains("Only the preview"))
    }

    @Test
    fun `a completed stream renders the reassembled body as a tree`() {
        val view = resolveBodyView(
            preview = cut,
            load = NetworkBodyLoad(
                text = whole,
                receivedBytes = whole.length,
                totalBytes = whole.length,
                loading = false,
                complete = true
            ),
            truncated = true,
            contentType = "application/json"
        )
        assertEquals(BodyRender.TREE, view.render, "A completed stream must reach the tree viewer")
        assertEquals(whole, view.text)
        assertNull(view.note)
    }

    @Test
    fun `a partial stream shows what has arrived and stays raw`() {
        val view = resolveBodyView(
            preview = cut,
            load = NetworkBodyLoad(text = cut, receivedBytes = cut.length, loading = true),
            truncated = true,
            contentType = "application/json"
        )
        assertEquals(BodyRender.RAW, view.render)
        assertEquals(cut, view.text)
        assertNull(view.note, "A stream in progress is not an error")
    }

    @Test
    fun `json without a json content type still renders as a tree`() {
        val view = resolveBodyView(
            preview = whole,
            load = null,
            truncated = false,
            contentType = "text/plain"
        )
        assertEquals(BodyRender.TREE, view.render, "Parsing is the ground truth, not the header")
    }

    @Test
    fun `an unanswered fetch is called out`() {
        val view = resolveBodyView(
            preview = cut,
            load = NetworkBodyLoad(loading = true, receivedBytes = 0),
            truncated = true,
            contentType = "application/json"
        )
        assertEquals(cut, view.text, "The preview stands in until bytes arrive")
        assertTrue(view.note!!.contains("Waiting for the device"))
    }

    @Test
    fun `an evicted body falls back to the preview and says so`() {
        val view = resolveBodyView(
            preview = cut,
            load = NetworkBodyLoad(loading = false, complete = true, unavailable = true),
            truncated = true,
            contentType = "application/json"
        )
        assertEquals(cut, view.text)
        assertTrue(view.note!!.contains("no longer retained"))
    }

    @Test
    fun `a non json body renders raw with no complaint`() {
        val view = resolveBodyView(
            preview = "<html><body>hi</body></html>",
            load = null,
            truncated = false,
            contentType = "text/html"
        )
        assertEquals(BodyRender.RAW, view.render)
        assertNull(view.note)
    }
}
