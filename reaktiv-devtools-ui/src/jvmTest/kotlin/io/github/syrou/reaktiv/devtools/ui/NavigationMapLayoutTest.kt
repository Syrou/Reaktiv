package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.devtools.ui.navmap.MapGraph
import io.github.syrou.reaktiv.devtools.ui.navmap.MapHit
import io.github.syrou.reaktiv.devtools.ui.navmap.MapLayout
import io.github.syrou.reaktiv.devtools.ui.navmap.MapModel
import io.github.syrou.reaktiv.devtools.ui.navmap.MapRect
import io.github.syrou.reaktiv.devtools.ui.navmap.MapRoute
import io.github.syrou.reaktiv.devtools.ui.navmap.MapStart
import io.github.syrou.reaktiv.devtools.ui.navmap.fitCamera
import io.github.syrou.reaktiv.devtools.ui.navmap.hitTest
import io.github.syrou.reaktiv.devtools.ui.navmap.layoutLinkMap
import io.github.syrou.reaktiv.devtools.ui.navmap.search
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

class NavigationMapLayoutTest {

    private val model = MapModel(
        graphs = listOf(
            MapGraph("root", "", null, MapStart.Route("home")),
            MapGraph("news", "news", "root", MapStart.Route("news/feed")),
            MapGraph("admin", "admin", "root", MapStart.Route("admin/reports"), guards = listOf(1)),
            MapGraph("audit", "admin/audit", "admin", MapStart.Route("admin/audit/log"), guards = listOf(1, 2)),
            MapGraph("settings", "settings", "root", MapStart.Route("settings/profile"))
        ),
        routes = listOf(
            MapRoute("home", "home", "root"),
            MapRoute("vip", "vip", "root", guards = listOf(1)),
            MapRoute("news/feed", "feed", "news"),
            MapRoute("news/article/{slug}", "article/{slug}", "news", params = listOf("slug")),
            MapRoute("admin/reports", "reports", "admin", guards = listOf(1)),
            MapRoute("admin/audit/log", "log", "audit", guards = listOf(1, 2)),
            MapRoute("settings/profile", "profile", "settings")
        )
    )

    private fun MapLayout.card(id: String) = cards.single { it.graph.id == id }

    @Test
    fun `cards never overlap`() {
        val layout = layoutLinkMap(model, emptySet())

        layout.cards.forEachIndexed { index, card ->
            layout.cards.drop(index + 1).forEach { other ->
                assertFalse(card.frame.intersects(other.frame), "${card.graph.id} overlaps ${other.graph.id}")
            }
        }
    }

    @Test
    fun `children sit in the column right of their parent`() {
        val layout = layoutLinkMap(model, emptySet())

        assertEquals(0, layout.card("root").depth)
        assertEquals(1, layout.card("admin").depth)
        assertEquals(2, layout.card("audit").depth)
        assertTrue(layout.card("audit").frame.left > layout.card("admin").frame.right)
    }

    @Test
    fun `a guard band holds its whole subtree and none of its siblings`() {
        val layout = layoutLinkMap(model, emptySet())
        val adminBand = layout.bands.single { it.graphId == "admin" }

        listOf("admin", "audit").forEach { id ->
            val frame = layout.card(id).frame
            assertTrue(adminBand.rect.contains(frame.left, frame.top) && adminBand.rect.contains(frame.right, frame.bottom), "$id outside")
        }
        listOf("news", "settings").forEach { id ->
            assertFalse(adminBand.rect.intersects(layout.card(id).frame), "$id inside the admin band")
        }
        val auditBand = layout.bands.single { it.graphId == "audit" }
        assertEquals(2, auditBand.guard)
        assertTrue(adminBand.rect.contains(auditBand.rect.left, auditBand.rect.top))
    }

    @Test
    fun `a route guarded beyond its card gets its own zone`() {
        val layout = layoutLinkMap(model, emptySet())

        val zones = layout.card("root").rowZones

        assertEquals(1, zones.size)
        assertEquals(listOf(1), zones.single().guards)
        assertEquals(layout.routeRects.getValue("vip"), zones.single().rect)
    }

    @Test
    fun `a collapsed graph hides its subtree and says how much is hidden`() {
        val layout = layoutLinkMap(model, setOf("admin"))

        val admin = layout.card("admin")
        assertTrue(admin.collapsed)
        assertEquals(2, admin.hiddenRoutes)
        assertEquals(1, admin.hiddenGraphs)
        assertTrue(layout.cards.none { it.graph.id == "audit" })
        assertFalse("admin/reports" in layout.routeRects)
    }

    @Test
    fun `a search match opens the graphs above it`() {
        val search = model.search("log")

        val layout = layoutLinkMap(model, setOf("admin"), expand = search.expand)

        assertEquals(setOf("admin/audit/log"), search.routes)
        assertTrue("admin/audit/log" in layout.routeRects)
    }

    @Test
    fun `hit testing finds routes graphs and toggles`() {
        val layout = layoutLinkMap(model, emptySet())
        val row = layout.routeRects.getValue("news/article/{slug}")
        val header = layout.card("news").header
        val toggle = assertNotNull(layout.card("news").toggle)

        assertEquals(MapHit.Route("news/article/{slug}"), layout.hitTest(row.left + 4f, row.centerY))
        assertEquals(MapHit.Graph("news"), layout.hitTest(header.left + 4f, header.centerY))
        assertEquals(MapHit.Toggle("news"), layout.hitTest(toggle.left + 2f, toggle.centerY))
        assertEquals(null, layout.hitTest(-500f, -500f))
    }

    @Test
    fun `fitting puts the whole map inside the view`() {
        val layout = layoutLinkMap(model, emptySet())

        val camera = fitCamera(layout.bounds, 800f, 600f, 24f)

        assertTrue(camera.toScreenX(layout.bounds.left) >= 23.9f)
        assertTrue(camera.toScreenX(layout.bounds.right) <= 776.1f)
        assertTrue(camera.toScreenY(layout.bounds.top) >= 0f)
        assertTrue(camera.toScreenY(layout.bounds.bottom) <= 600f)
    }

    @Test
    fun `a large app lays out quickly`() {
        val graphs = listOf(MapGraph("root", "", null, MapStart.Route("r0"))) +
            (0 until 50).map { MapGraph("g$it", "g$it", if (it < 10) "root" else "g${it % 10}", MapStart.Route("g$it/s0")) }
        val routes = (0 until 500).map { index ->
            val graph = graphs[1 + index % 50]
            MapRoute("${graph.path}/s$index", "s$index", graph.id)
        }
        val big = MapModel(graphs, routes)

        val elapsed = measureTime { layoutLinkMap(big, emptySet()) }

        assertTrue(elapsed.inWholeMilliseconds < 500, "layout took $elapsed")
        val layout = layoutLinkMap(big, emptySet())
        assertEquals(500, layout.routeRects.size)
        assertEquals(MapRect(layout.bounds.left, layout.bounds.top, layout.bounds.right, layout.bounds.bottom), layout.bounds)
    }
}
