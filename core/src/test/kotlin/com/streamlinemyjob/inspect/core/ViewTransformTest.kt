package com.streamlinemyjob.inspect.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The zoom maths (4.28, C31): the screen's mapping from pixels to drawing units gains a zoom and an offset; the hit test is unchanged. */
class ViewTransformTest {
    private fun random(test: String): Random = TestRandom.forTest("${ViewTransformTest::class.qualifiedName}.$test")

    private val w = 800.0
    private val h = 1120.0
    private val scale = w / 1000.0

    @Test
    fun noZoomIsTheOriginalMapping() {
        val (ux, uy) = ViewTransform().toUnits(400.0, 560.0, scale)
        assertEquals(500.0, ux, 1e-9)
        assertEquals(700.0, uy, 1e-9)
    }

    @Test
    fun unitsAndPixelsRoundTripUnderAnyZoomAndOffset() {
        val r = random("roundTrip")
        repeat(500) {
            var t = ViewTransform()
            repeat(1 + r.nextInt(6)) {
                t = if (r.nextBoolean()) t.zoomedBy(0.5 + r.nextDouble() * 2.0, r.nextDouble() * w, r.nextDouble() * h, w, h)
                else t.pannedBy((r.nextDouble() - 0.5) * 600, (r.nextDouble() - 0.5) * 600, w, h)
            }
            val ux = r.nextDouble() * 1000
            val uy = r.nextDouble() * 1400
            val (px, py) = t.toPixels(ux, uy, scale)
            val (bx, by) = t.toUnits(px, py, scale)
            assertEquals(ux, bx, 1e-6)
            assertEquals(uy, by, 1e-6)
        }
    }

    @Test
    fun zoomingKeepsThePointUnderTheFingersWhereItWas() {
        val r = random("focus")
        repeat(300) {
            val t = ViewTransform().zoomedBy(1.5 + r.nextDouble(), r.nextDouble() * w, r.nextDouble() * h, w, h)
            val fx = r.nextDouble() * w
            val fy = r.nextDouble() * h
            val before = t.toUnits(fx, fy, scale)
            val after = t.zoomedBy(1.0 + r.nextDouble(), fx, fy, w, h)
            // The focus keeps its drawing point, unless the clamp had to move the picture (zoom at its limits or an edge).
            val moved = after.toUnits(fx, fy, scale)
            val atEdge = after.offsetX == 0.0 || after.offsetY == 0.0 || after.offsetX == w - w * after.zoom || after.offsetY == h - h * after.zoom
            if (!atEdge && after.zoom != t.zoom) {
                assertEquals(before.first, moved.first, 1e-6)
                assertEquals(before.second, moved.second, 1e-6)
            }
        }
    }

    @Test
    fun theZoomStaysBetweenItsLimitsAndThePictureAlwaysCoversTheCanvas() {
        val r = random("limits")
        var t = ViewTransform()
        repeat(500) {
            t = if (r.nextBoolean()) t.zoomedBy(0.3 + r.nextDouble() * 3, r.nextDouble() * w, r.nextDouble() * h, w, h)
            else t.pannedBy((r.nextDouble() - 0.5) * 2000, (r.nextDouble() - 0.5) * 2000, w, h)
            assertTrue(t.zoom in ViewTransform.MIN_ZOOM..ViewTransform.MAX_ZOOM)
            assertTrue(t.offsetX <= 0.0 && t.offsetX >= w - w * t.zoom - 1e-9)
            assertTrue(t.offsetY <= 0.0 && t.offsetY >= h - h * t.zoom - 1e-9)
            // The canvas's corners are inside the frame.
            val (x0, y0) = t.toUnits(0.0, 0.0, scale)
            val (x1, y1) = t.toUnits(w, h, scale)
            assertTrue(x0 >= -1e-6 && y0 >= -1e-6 && x1 <= 1000 + 1e-6 && y1 <= 1400 + 1e-6)
        }
    }

    @Test
    fun aTapUnderZoomResolvesToTheSameRegionAsWithoutZoom() {
        val drawing = DrawingFixtures.confirmedDrawing
        val r = random("sameRegion")
        repeat(300) {
            val region = drawing.regions[r.nextInt(drawing.regions.size)]
            val box = region.points
            var ux: Double
            var uy: Double
            do {
                ux = box.minOf { it.x } + r.nextDouble() * (box.maxOf { it.x } - box.minOf { it.x })
                uy = box.minOf { it.y } + r.nextDouble() * (box.maxOf { it.y } - box.minOf { it.y })
            } while (Hit.resolve(drawing, ux, uy) != region.id)
            var t = ViewTransform().zoomedBy(1.5 + r.nextDouble() * 3, r.nextDouble() * w, r.nextDouble() * h, w, h)
            t = t.pannedBy((r.nextDouble() - 0.5) * 400, (r.nextDouble() - 0.5) * 400, w, h)
            val (px, py) = t.toPixels(ux, uy, scale)
            // Only points still on the canvas can be tapped.
            if (px in 0.0..w && py in 0.0..h) {
                val (bx, by) = t.toUnits(px, py, scale)
                assertEquals(region.id, Hit.resolve(drawing, bx, by))
            }
        }
    }
}
