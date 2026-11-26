package com.streamlinemyjob.inspect.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Resolving a tap (designs/stage-1-foundation.md 4.21): the worked-example table, row for row, and properties checked by an independent winding-number rule. */
class HitTest {
    private fun random(test: String): Random = TestRandom.forTest("${HitTest::class.qualifiedName}.$test")

    private val drawing get() = DrawingFixtures.confirmedDrawing

    /** 4.21's table: point in units, the region it resolves to as units, and as a dot (null: nothing). */
    private class Example(val x: Double, val y: Double, val units: String?, val dot: String? = units)

    private val examples: List<Example> = listOf(
        Example(500.0, 374.0, "bonnet"),
        Example(913.0, 424.0, "ds-front-alloy"),
        Example(913.0, 476.0, "ds-front-alloy"),
        Example(842.0, 424.0, null),
        Example(792.0, 419.0, "ds-front-wing"),
        Example(500.0, 232.0, "grille", null),
        Example(500.0, 216.0, null),
        Example(500.0, 180.0, "front-bumper"),
        Example(362.0, 254.0, "ps-headlight"),
        Example(638.0, 254.0, "ds-headlight"),
        Example(421.0, 254.0, null),
        Example(500.0, 611.0, null),
        Example(500.0, 738.0, "roof"),
        Example(825.0, 611.0, "ds-front-door"),
        Example(825.0, 716.0, null),
        Example(893.0, 700.0, null),
        Example(87.0, 947.0, "ps-rear-alloy"),
        Example(735.0, 900.0, null),
        Example(634.0, 1153.0, "ds-tail-light"),
        Example(366.0, 1197.0, null),
        Example(500.0, 60.0, null),
        Example(1000.0, 1400.0, null),
        Example(1001.0, 700.0, null),
    )

    @Test
    fun workedExamplesAsUnits() {
        assertEquals(23, examples.size)
        for (e in examples) assertEquals("(${e.x}, ${e.y})", e.units, Hit.resolve(drawing, e.x, e.y))
    }

    @Test
    fun workedExamplesAsDots() {
        // The same points divided by the frame's width and height (the table's dots are these rounded to four places).
        // The (500, 232) row's two results differ, as 4.21 says: on the grille's edge as units, in the gap above it as a dot.
        for (e in examples) {
            assertEquals("dot of (${e.x}, ${e.y})", e.dot, Hit.resolveDot(drawing, e.x / 1000.0, e.y / 1400.0))
        }
    }

    @Test
    fun theFrameEdgeIsInsideTheFrameAndJustOutsideIsNot() {
        assertNull(Hit.resolve(drawing, -0.001, 700.0))
        assertNull(Hit.resolve(drawing, 1000.001, 700.0))
        assertNull(Hit.resolve(drawing, 500.0, -0.001))
        assertNull(Hit.resolve(drawing, 500.0, 1400.001))
        assertNull(Hit.resolve(drawing, Double.NaN, 500.0))
        assertNull(Hit.resolve(drawing, 500.0, Double.NaN))
        assertNull(Hit.resolve(drawing, Double.POSITIVE_INFINITY, 500.0))
        assertNull(Hit.resolve(drawing, 0.0, 0.0)) // the corner is inside the frame and on no region
        // A region touching the frame edge counts there: a fixture square from 0 to 100.
        val d = DrawingFixtures.parse(DrawingFixtures.drawing(regions = DrawingFixtures.region("e", DrawingFixtures.square(0, 0, 100), "[50,50]")))
        assertEquals("e", Hit.resolve(d, 0.0, 0.0))
        assertEquals("e", Hit.resolve(d, 0.0, 50.0))
        assertNull(Hit.resolve(d, -0.5, 50.0))
    }

    @Test
    fun aPointOnTheEdgeTwoRegionsShareGoesToTheEarlierOne() {
        // Regions that share an edge are not a valid drawing (DRAWING_GAP), but Hit still resolves them the same way every time.
        val ra = Region("a", Pt(150, 150), listOf(Pt(100, 100), Pt(200, 100), Pt(200, 200), Pt(100, 200)))
        val rb = Region("b", Pt(250, 150), listOf(Pt(200, 100), Pt(300, 100), Pt(300, 200), Pt(200, 200)))
        val ab = Drawing("t", 1000, 1000, listOf(ra, rb), emptyList(), emptyList())
        val ba = Drawing("t", 1000, 1000, listOf(rb, ra), emptyList(), emptyList())
        assertEquals("a", Hit.resolve(ab, 200.0, 150.0))
        assertEquals("b", Hit.resolve(ba, 200.0, 150.0))
        assertEquals("a", Hit.resolve(ab, 200.0, 100.0)) // the shared corner too
        assertEquals("b", Hit.resolve(ab, 200.5, 150.0))
        assertEquals("a", Hit.resolve(ab, 199.999, 150.0))
    }

    /**
     * On a grid every [step] units over the frame: every point `resolve` gives a region lies inside that
     * region by the winding rule and inside no earlier region; every point it gives `null` lies inside no
     * region. Returns the number of points looked at.
     */
    private fun scan(d: Drawing, step: Int): Int {
        var points = 0
        var x = 0
        while (x <= d.width) {
            var y = 0
            while (y <= d.height) {
                points++
                val got = Hit.resolve(d, x.toDouble(), y.toDouble())
                val containing = d.regions.indexOfFirst { Winding.inside(it.points, x, y) }
                assertTrue("($x, $y) is inside two regions", d.regions.count { Winding.inside(it.points, x, y) } <= 1)
                if (got == null) {
                    assertEquals("($x, $y) resolves to nothing but is inside ${d.regions.getOrNull(containing)?.id}", -1, containing)
                } else {
                    val index = d.regions.indexOfFirst { it.id == got }
                    assertTrue("($x, $y) resolves to $got, which does not contain it", Winding.inside(d.regions[index].points, x, y))
                    assertEquals("($x, $y) resolves to $got but an earlier region contains it", index, containing)
                }
                y += step
            }
            x += step
        }
        return points
    }

    @Test
    fun theGridEvery5UnitsAgreesWithTheWindingRule() {
        assertEquals(201 * 281, scan(drawing, 5))
    }

    @Test
    fun theGridAgreesOnRandomDrawingsToo() {
        val r = random("random-drawings")
        repeat(25) {
            val d = randomDrawing(r)
            assertTrue(scan(d, 7) > 0)
        }
    }

    @Test
    fun everyRegionAnchorAndItsSixteenPointsResolveToIt() {
        for (region in drawing.regions) {
            assertEquals(region.id, Hit.resolve(drawing, region.anchor.x.toDouble(), region.anchor.y.toDouble()))
            for (k in 0 until 16) {
                val angle = Math.toRadians(22.5 * k)
                val x = region.anchor.x + 20.0 * Math.cos(angle)
                val y = region.anchor.y + 20.0 * Math.sin(angle)
                assertEquals("${region.id} at ${22.5 * k} degrees", region.id, Hit.resolve(drawing, x, y))
            }
        }
    }

    companion object {
        /** A valid drawing of random frame size and 1 to 8 rectangles of random size, each in its own cell with 17 units of margin (so no two are closer than 32), listed in random order. */
        fun randomDrawing(r: Random): Drawing {
            val width = r.nextInt(450, 1500)
            val height = r.nextInt(450, 1500)
            val cols = r.nextInt(1, 4)
            val rows = r.nextInt(1, 4)
            val cellW = width / cols
            val cellH = height / rows
            val cells = (0 until cols).flatMap { c -> (0 until rows).map { rw -> c to rw } }.shuffled(r).take(r.nextInt(1, 9))
            val regions = cells.mapIndexed { i, (c, rw) ->
                val w = r.nextInt(60, cellW - 34)
                val h = r.nextInt(60, cellH - 34)
                val x = c * cellW + 17 + r.nextInt(0, cellW - 34 - w + 1)
                val y = rw * cellH + 17 + r.nextInt(0, cellH - 34 - h + 1)
                DrawingFixtures.region("r$i", "[[$x,$y],[${x + w},$y],[${x + w},${y + h}],[$x,${y + h}]]", "[${x + w / 2},${y + h / 2}]")
            }
            val text = DrawingFixtures.drawing(width = "$width", height = "$height", regions = regions.joinToString(","), texts = """[{"text":"X","at":[${width / 2},${height / 2}]}]""")
            return DrawingFixtures.parse(text)
        }
    }
}
