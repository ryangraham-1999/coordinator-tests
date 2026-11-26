package com.streamlinemyjob.inspect.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamlinemyjob.inspect.TestHooks
import com.streamlinemyjob.inspect.core.Hit
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.data.Ok
import com.streamlinemyjob.inspect.testdata.PointSampler
import com.streamlinemyjob.inspect.testdata.Snapshot
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.drawing
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.has
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.isSelectedNow
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.statusText
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.tapUnits
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.waitSelected
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.waitStatus
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.activate
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.back
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.click
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.container
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.launch
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.random
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.snapshot
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForNoTag
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForTag
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.db
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random
import kotlin.math.abs

/**
 * S1.28's `TapPickTest` (designs/stage-1-foundation.md 4.25, S1.28 "Done when"), with the confirmed seed and links and with a
 * generated seed, generated links and `Mutation.apply`. Taps `PointSampler` points in 3 random regions linked to offered values:
 * the value's chip is selected and `drawing-status` shows text; each is saved with a random damage. Taps in a region that is not
 * linked, in a region whose value was removed, at (500, 60) and in the gap between two doors leave the area (and dot) as they
 * were; the gap tap shows the missed-tap warning, which the next tap on a part removes. Picking a chip after a tap saves no dot.
 * After `TestHooks.reopen()` each saved dot is within one pixel of the tapped point and resolves to the tapped region, and
 * `finding-dot:<id>` shows exactly on the rows of findings with a dot.
 */
@RunWith(AndroidJUnit4::class)
class TapPickTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun rnd(test: String): Random = random("${TapPickTest::class.qualifiedName}.$test")

    @Test
    fun withTheConfirmedSeedAndLinks() = run(rnd("confirmed"), generated = false)

    @Test
    fun withAGeneratedSeedGeneratedLinksAndTheMutation() = run(rnd("generated"), generated = true)

    private class Tapped(val region: String, val value: String, val ux: Double, val uy: Double)

    private fun <T> List<T>.pick(random: Random): T = this[random.nextInt(size)]

    private fun run(random: Random, generated: Boolean) {
        DrawingTestSupport.setUp(random, generated)
        val repository = container.repository()
        val (vehicleId, inspectionId) = DrawingTestSupport.vehicleWithInspection(random)
        val damages = repository.offeredValues(Role.DAMAGE)
        val linked = DrawingTestSupport.linkedAndOffered()
        assertTrue("no region is linked to an offered value", linked.isNotEmpty())
        val regions = linked.keys.toList().shuffled(random)
        val tapped = ArrayList<Tapped>()
        var saved = 0
        // The canvas's size in pixels, read while it is on screen: the tests tap in drawing units and compare in fractions of it.
        var canvasPixels: Pair<Int, Int>? = null

        // A region that is not linked: one is unlinked first if none is (S1.29's repository call does not exist yet, so by SQL).
        val unlinked = repository.regionLinks().filterValues { it == null }.keys.firstOrNull()
            ?: regions.last().also { region ->
                db().openHelper.writableDatabase.execSQL(
                    "UPDATE region_link SET value_id = NULL WHERE drawing_id = ? AND region_id = ?", arrayOf(drawing.id, region),
                )
            }
        val usable = linked.filterKeys { it != unlinked }
        assertTrue("every region was unlinked", usable.isNotEmpty())
        val taps = usable.keys.toList().shuffled(random).take(3)

        launch().use { scenario ->
            compose.click("vehicle-row:$vehicleId")
            compose.click("inspection-row:$inspectionId")
            compose.waitForTag("finding-add")

            // Three findings by a tap, each saved with a random damage. The last one also gets the taps that must change nothing.
            taps.forEachIndexed { i, region ->
                val value = usable.getValue(region)
                compose.click("finding-add")
                compose.waitForTag("save")
                canvasPixels = DrawingTestSupport.canvasSize(compose).let { it.width to it.height }
                var p = PointSampler.inside(random, drawing, region)
                tapUnits(compose, p.x, p.y)
                waitSelected(compose, "value:$value")
                assertTrue("the status line is empty", !statusText(compose).isNullOrBlank())
                assertTrue("a dot should be set", has(compose, "dot-remove"))

                if (i == taps.lastIndex) {
                    // Off the car, in a region that is not linked: nothing changes.
                    tapUnits(compose, 500.0, 60.0)
                    waitForMissed()
                    assertNothingChanged(value)
                    val unlinkedPoint = PointSampler.inside(random, drawing, unlinked)
                    tapUnits(compose, unlinkedPoint.x, unlinkedPoint.y)
                    waitStatus(compose, DrawingTexts.NOT_LINKED_EDITOR)
                    assertFalse("the missed-tap warning is still shown", has(compose, "drawing-missed"))
                    assertNothingChanged(value)
                    // The gap between two doors: the area and dot stay, and the warning shows.
                    tapUnits(compose, 825.0, 716.0)
                    waitForMissed()
                    assertEquals(DrawingTexts.MISSED, statusText(compose))
                    assertNothingChanged(value)
                    // The next tap on a part removes the warning (and moves the dot there).
                    p = PointSampler.inside(random, drawing, region)
                    tapUnits(compose, p.x, p.y)
                    compose.waitForNoTag("drawing-missed")
                    waitSelected(compose, "value:$value")
                }
                tapped += Tapped(region, value, p.x, p.y)
                compose.activate("value:${damages.pick(random).valueId}")
                DrawingTestSupport.saveAndWait(compose, ++saved)
            }

            // Picking a chip after a tap clears the dot: the finding is saved with the chip's area and no dot.
            val before = snapshot().findings.map { it.id }.toSet()
            val tappedRegion = usable.keys.toList().pick(random)
            val offered = repository.offeredValues(Role.AREA)
            val chipValue = offered.firstOrNull { it.valueId != usable.getValue(tappedRegion) && tapped.none { t -> t.value == it.valueId } }
                ?: offered.first { it.valueId != usable.getValue(tappedRegion) }
            compose.click("finding-add")
            compose.waitForTag("save")
            val q = PointSampler.inside(random, drawing, tappedRegion)
            tapUnits(compose, q.x, q.y)
            waitSelected(compose, "value:${usable.getValue(tappedRegion)}")
            compose.activate("area-list-tab:${chipValue.listId}")
            compose.activate("value:${chipValue.valueId}")
            waitSelected(compose, "value:${chipValue.valueId}")
            compose.waitForNoTag("dot-remove")
            compose.activate("value:${damages.pick(random).valueId}")
            DrawingTestSupport.saveAndWait(compose, ++saved)
            val pickedFromList = snapshot().findings.single { it.id !in before }
            assertEquals(chipValue.valueId, pickedFromList.areaValueId)
            assertNull("a finding picked from the list has no dot", pickedFromList.dotX)

            // A region whose value was removed through the repository: the tap leaves the area as it was, and the line says so.
            val removedRegion = usable.keys.firstOrNull { region ->
                val value = usable.getValue(region)
                repository.removeValue(value) is Ok
            } ?: error("no linked value could be removed")
            val removedValue = usable.getValue(removedRegion)
            val label = repository.areaLabel(removedValue)!!
            val stillOffered = repository.offeredValues(Role.AREA).first()
            val untouched = snapshot()
            compose.click("finding-add")
            compose.waitForTag("save")
            compose.activate("area-list-tab:${stillOffered.listId}")
            compose.activate("value:${stillOffered.valueId}")
            waitSelected(compose, "value:${stillOffered.valueId}")
            val r = PointSampler.inside(random, drawing, removedRegion)
            tapUnits(compose, r.x, r.y)
            waitStatus(compose, DrawingTexts.removedEditor(label))
            assertTrue(isSelectedNow(compose, "value:${stillOffered.valueId}"))
            assertFalse("a dot was made on a removed value", has(compose, "dot-remove"))
            back(scenario)
            compose.waitForTag("finding-add")
            Snapshot.assertSame(untouched, snapshot(), "tapping a removed value's region changed the data")
        }

        // After a reopen each saved dot is where it was tapped (within one pixel) and resolves to the tapped region.
        TestHooks.reopen()
        val after = snapshot()
        val size = canvasPixels ?: error("the canvas was never measured")
        for (t in tapped) {
            val fx = t.ux / drawing.width
            val fy = t.uy / drawing.height
            val match = after.findings.filter { it.dotX != null && abs(it.dotX!! - fx) <= 1.0 / size.first && abs(it.dotY!! - fy) <= 1.0 / size.second }
            assertEquals("the dot of the tap in ${t.region}", 1, match.size)
            val f = match.single()
            assertEquals(t.value, f.areaValueId)
            assertEquals(drawing.id, f.dotDrawing)
            assertEquals(t.region, Hit.resolveDot(drawing, f.dotX!!, f.dotY!!))
        }
        assertEquals(tapped.size, after.findings.count { it.dotX != null })
        launchAndCheckRows(vehicleId, inspectionId, after)
    }

    private fun launchAndCheckRows(vehicleId: String, inspectionId: String, after: Snapshot) {
        launch().use { _ ->
            compose.click("vehicle-row:$vehicleId")
            compose.click("inspection-row:$inspectionId")
            for (f in after.findings) {
                DrawingTestSupport.scrollToTag(compose, "finding-row:${f.id}")
                assertEquals("finding-dot:${f.id} on row ${f.id}", f.dotX != null, has(compose, "finding-dot:${f.id}"))
            }
        }
    }

    private fun waitForMissed() {
        compose.waitForTag("drawing-missed")
    }

    private fun assertNothingChanged(value: String) {
        assertTrue("the area changed", isSelectedNow(compose, "value:$value"))
        assertTrue("the dot was removed", has(compose, "dot-remove"))
    }
}
