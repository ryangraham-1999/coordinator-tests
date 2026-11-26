package com.streamlinemyjob.inspect.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.up
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamlinemyjob.inspect.core.Hit
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.testdata.DrawingPoint
import com.streamlinemyjob.inspect.testdata.PointSampler
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.drawing
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.has
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.onCanvas
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.pixelOf
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.waitSelected
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.WAIT_MS
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.activate
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.click
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.container
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.launch
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.random
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForNoTag
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForTag
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitLabelled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * S1.28's `ZoomTest` (designs/stage-1-foundation.md 4.28, C31 c): pinches in, drags, then taps a `PointSampler` point (mapped
 * through the zoom and offset) and the tap resolves to the same region as without zoom; touch-and-hold shows the magnifier and a
 * release on a point picks the region under it.
 */
@RunWith(AndroidJUnit4::class)
class ZoomTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun rnd(test: String): Random = random("${ZoomTest::class.qualifiedName}.$test")

    private fun <T> List<T>.pick(random: Random): T = this[random.nextInt(size)]

    @Test
    fun withTheConfirmedSeed() = run(rnd("confirmed"), generated = false)

    @Test
    fun withAGeneratedSeed() = run(rnd("generated"), generated = true)

    private fun run(random: Random, generated: Boolean) {
        DrawingTestSupport.setUp(random, generated)
        val repository = container.repository()
        val (vehicleId, inspectionId) = DrawingTestSupport.vehicleWithInspection(random)
        val usable = DrawingTestSupport.linkedAndOffered()
        assertTrue("no region is linked to an offered value", usable.size >= 2)
        val areas = repository.offeredValues(Role.AREA)

        launch().use { _ ->
            compose.click("vehicle-row:$vehicleId")
            compose.click("inspection-row:$inspectionId")
            compose.click("finding-add")
            compose.waitForTag("save")
            val canvas = DrawingTestSupport.canvasSize(compose)

            // Without zoom: a tap at a point of a region picks that region's value.
            val region = usable.keys.toList().pick(random)
            val value = usable.getValue(region)
            val p = PointSampler.inside(random, drawing, region)
            DrawingTestSupport.tapUnits(compose, p.x, p.y)
            waitSelected(compose, "value:$value")
            // Move the area somewhere else, so the tap under zoom visibly picks it again.
            val other = areas.first { it.valueId != value }
            compose.activate("area-list-tab:${other.listId}")
            compose.activate("value:${other.valueId}")
            waitSelected(compose, "value:${other.valueId}")

            // Pinch in around the point (the fingers kept on the canvas), then drag the picture a little towards the middle.
            val at = pixelOf(compose, p.x, p.y)
            val centre = Offset(at.x.coerceIn(120f, canvas.width - 120f), at.y.coerceIn(120f, canvas.height - 120f))
            compose.onNodeWithCanvas().performTouchInput {
                pinch(
                    Offset(centre.x - 40f, centre.y), Offset(centre.x + 40f, centre.y),
                    Offset(centre.x - 110f, centre.y), Offset(centre.x + 110f, centre.y),
                )
            }
            compose.waitLabelled("the pinch to zoom in (zoom is ${DrawingTestSupport.view(compose).zoom})") { DrawingTestSupport.view(compose).zoom > 1.2 }
            val zoomed = DrawingTestSupport.view(compose)
            assertTrue("zoom ${zoomed.zoom}", zoomed.zoom > 1.2)
            // The point may have moved off the canvas (the fingers were kept on it, the point may not be): drag it back in.
            repeat(8) {
                val where = pixelOf(compose, p.x, p.y)
                if (onCanvas(compose, where, inset = 40f)) return@repeat
                val a = Offset(canvas.width / 2f, canvas.height / 2f)
                val b = Offset(
                    (a.x + (a.x - where.x)).coerceIn(10f, canvas.width - 10f),
                    (a.y + (a.y - where.y)).coerceIn(10f, canvas.height - 10f),
                )
                compose.onNodeWithCanvas().performTouchInput { swipe(a, b, 300) }
            }
            val now = pixelOf(compose, p.x, p.y)
            val middle = Offset(canvas.width / 2f, canvas.height / 2f)
            val step = Offset((middle.x - now.x).coerceIn(-40f, 40f), (middle.y - now.y).coerceIn(-40f, 40f))
            val from = Offset(middle.x, middle.y)
            compose.onNodeWithCanvas().performTouchInput { swipe(from, from + step, 300) }
            val moved = pixelOf(compose, p.x, p.y)
            assertTrue("the point is on the canvas after the drag ($moved)", onCanvas(compose, moved))
            assertTrue("the picture did not move", DrawingTestSupport.view(compose).let { it.offsetX != zoomed.offsetX || it.offsetY != zoomed.offsetY } || step == Offset.Zero)

            // The same drawing point, tapped through the zoom and offset, resolves to the same region as without zoom.
            DrawingTestSupport.tapUnits(compose, p.x, p.y)
            waitSelected(compose, "value:$value")
            assertEquals(region, Hit.resolve(drawing, p.x, p.y))

            // Touch and hold shows the magnifier; sliding to a point of another part and letting go picks the part under it.
            val targets = usable.keys.filter { it != region }
            var target: Pair<String, DrawingPoint>? = null
            for (candidate in targets.shuffled(random)) {
                repeat(40) {
                    if (target == null) {
                        val q = PointSampler.inside(random, drawing, candidate)
                        if (onCanvas(compose, pixelOf(compose, q.x, q.y), inset = 24f)) target = candidate to q
                    }
                }
                if (target != null) break
            }
            val (targetRegion, q) = target ?: error("no other linked region has a point on the zoomed canvas")
            compose.activate("area-list-tab:${other.listId}")
            compose.activate("value:${other.valueId}")
            waitSelected(compose, "value:${other.valueId}")
            val start = pixelOf(compose, p.x, p.y)
            val end = pixelOf(compose, q.x, q.y)
            compose.onNodeWithCanvas().performTouchInput {
                down(start)
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
                moveTo(Offset(start.x + 2f, start.y + 2f))
            }
            compose.waitForTag("drawing-magnifier")
            compose.onNodeWithCanvas().performTouchInput { moveTo(end) }
            // While aiming, the magnifier highlights the part under its crosshair: the one the release will pick.
            compose.waitLabelled("the magnifier to highlight $targetRegion") {
                compose.onNodeWithTag("drawing-magnifier").fetchSemanticsNode().config
                    .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.StateDescription) { null } == targetRegion
            }
            compose.onNodeWithCanvas().performTouchInput { up() }
            compose.waitForNoTag("drawing-magnifier")
            // The release made a dot: saved, it resolves to the part under the finger (the generated links may link every part to
            // one value, so the area alone cannot tell the parts apart).
            compose.waitForTag("dot-remove")
            waitSelected(compose, "value:${usable.getValue(targetRegion)}")
            compose.activate("value:${repository.offeredValues(Role.DAMAGE).pick(random).valueId}")
            DrawingTestSupport.saveAndWait(compose, 1)
            val saved = com.streamlinemyjob.inspect.ui.ScreenTestSupport.snapshot().findings.single()
            assertEquals(targetRegion, Hit.resolveDot(drawing, saved.dotX!!, saved.dotY!!))
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithCanvas() = waitForTag("car-drawing")
}
