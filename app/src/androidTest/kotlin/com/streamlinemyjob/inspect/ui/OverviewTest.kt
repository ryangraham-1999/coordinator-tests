package com.streamlinemyjob.inspect.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamlinemyjob.inspect.core.ActionChoice
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.data.Dot
import com.streamlinemyjob.inspect.data.Ok
import com.streamlinemyjob.inspect.testdata.PointSampler
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.drawing
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.has
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.matching
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.tapUnits
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.waitSelected
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.WAIT_MS
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.activate
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.back
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.click
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.container
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.launch
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.random
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.snapshot
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForData
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForTag
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitLabelled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * S1.28's `OverviewTest` (designs/stage-1-foundation.md 4.28): 5 findings made by 5 taps on the Inspection screen's drawing (each
 * labelled and saved) show exactly 5 numbered markers `finding-dot:<n>`, numbered like the findings list; tapping a marker opens
 * that finding; removing a finding's dot removes its marker; a finding picked from the lists has none. The Vehicle screen with 3
 * inspections shows every dot of all 3, the current inspection's solid and the others paler, and no more.
 */
@RunWith(AndroidJUnit4::class)
class OverviewTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun rnd(test: String): Random = random("${OverviewTest::class.qualifiedName}.$test")

    private fun <T> List<T>.pick(random: Random): T = this[random.nextInt(size)]

    private val markerNumber = Regex("finding-dot:\\d+")

    @Test
    fun markersWithTheConfirmedSeed() = markers(rnd("markers.confirmed"), generated = false)

    @Test
    fun markersWithAGeneratedSeed() = markers(rnd("markers.generated"), generated = true)

    @Test
    fun vehicleScreenWithTheConfirmedSeed() = vehicleScreen(rnd("vehicle.confirmed"), generated = false)

    @Test
    fun vehicleScreenWithAGeneratedSeed() = vehicleScreen(rnd("vehicle.generated"), generated = true)

    private fun markers(random: Random, generated: Boolean) {
        DrawingTestSupport.setUp(random, generated)
        val repository = container.repository()
        val (vehicleId, inspectionId) = DrawingTestSupport.vehicleWithInspection(random)
        val usable = DrawingTestSupport.linkedAndOffered()
        assertTrue("no region is linked to an offered value", usable.isNotEmpty())
        val damages = repository.offeredValues(Role.DAMAGE)

        launch().use { scenario ->
            compose.click("vehicle-row:$vehicleId")
            compose.click("inspection-row:$inspectionId")
            compose.waitForTag("car-drawing")

            // 5 findings by 5 taps: a tap on a part starts a new finding with that dot and area; label it and save.
            for (i in 1..5) {
                val region = usable.keys.toList().pick(random)
                val p = PointSampler.inside(random, drawing, region)
                compose.waitForTag("finding-add")
                tapUnits(compose, p.x, p.y)
                compose.waitForTag("save")
                waitSelected(compose, "value:${usable.getValue(region)}")
                assertTrue("the new finding has no dot", has(compose, "dot-remove"))
                compose.activate("value:${damages.pick(random).valueId}")
                DrawingTestSupport.saveAndWait(compose, i)
            }

            // Exactly 5 markers, numbered like the findings list (by when each was made, as the list is).
            val list = snapshot().findings.sortedWith(compareBy({ it.createdAt }, { it.id }))
            assertEquals(5, list.size)
            for (f in list) {
                DrawingTestSupport.scrollToTag(compose, "finding-row:${f.id}")
                assertTrue("row dot of ${f.id}", has(compose, "finding-dot:${f.id}"))
            }
            waitMarkers(5)
            for (n in 1..5) assertTrue("marker $n", has(compose, "finding-dot:$n"))

            // Tapping a marker opens that finding.
            compose.activate("finding-dot:2")
            compose.waitForTag("save")
            waitSelected(compose, "value:${list[1].areaValueId}")
            assertTrue(has(compose, "dot-remove"))
            back(scenario)
            compose.waitForTag("finding-add")
            waitMarkers(5)

            // A finding picked from the lists has no marker.
            val chip = repository.offeredValues(Role.AREA).pick(random)
            compose.click("finding-add")
            compose.waitForTag("save")
            compose.activate("area-list-tab:${chip.listId}")
            compose.activate("value:${chip.valueId}")
            compose.activate("value:${damages.pick(random).valueId}")
            DrawingTestSupport.saveAndWait(compose, 6)
            val listed = snapshot().findings.single { f -> list.none { it.id == f.id } }
            DrawingTestSupport.scrollToTag(compose, "finding-row:${listed.id}")
            assertFalse("a finding picked from the lists has a row dot", has(compose, "finding-dot:${listed.id}"))
            waitMarkers(5)
            assertFalse(has(compose, "finding-dot:6"))

            // Removing a finding's dot removes its marker; the others keep their numbers.
            compose.activate("finding-dot:3")
            compose.waitForTag("save")
            compose.activate("dot-remove")
            compose.activate("save")
            compose.waitLabelled("the Inspection screen or the same-area dialog") { has(compose, "finding-add") || has(compose, "warning-dialog") }
            if (has(compose, "warning-dialog")) compose.activate("confirm")
            compose.waitForTag("finding-add")
            compose.waitForData { s -> s.findings.single { it.id == list[2].id }.dotX == null }
            waitMarkers(4)
            assertFalse(has(compose, "finding-dot:3"))
            for (n in listOf(1, 2, 4, 5)) assertTrue("marker $n", has(compose, "finding-dot:$n"))
            DrawingTestSupport.scrollToTag(compose, "finding-row:${list[2].id}")
            assertFalse(has(compose, "finding-dot:${list[2].id}"))
        }
    }

    private fun waitMarkers(count: Int) {
        DrawingTestSupport.scrollToTag(compose, "car-drawing")
        compose.waitLabelled("$count numbered markers (now ${matching(compose, markerNumber)})") { matching(compose, markerNumber) == count }
        assertEquals(count, matching(compose, markerNumber))
    }

    private fun vehicleScreen(random: Random, generated: Boolean) {
        DrawingTestSupport.setUp(random, generated)
        val repository = container.repository()
        val usable = DrawingTestSupport.linkedAndOffered()
        assertTrue("no region is linked to an offered value", usable.isNotEmpty())
        val damages = repository.offeredValues(Role.DAMAGE)
        val vehicle = (repository.addVehicle(com.streamlinemyjob.inspect.testdata.RegGenerator(random).next().typed) as Ok).value

        // Three inspections, dates and mileages going up, each with 1 to 3 findings with dots; the last is the current one.
        class Made(val findingId: String, val inspectionIndex: Int)
        val made = ArrayList<Made>()
        val dates = listOf("01/03/2026", "01/06/2026", "01/09/2026")
        dates.forEachIndexed { index, date ->
            val inspection = (repository.addInspection(vehicle, date, "${(index + 1) * 1000}") as Ok).value
            repeat(1 + random.nextInt(3)) {
                val region = usable.keys.toList().pick(random)
                val p = PointSampler.inside(random, drawing, region)
                val result = repository.addFinding(
                    inspection, usable.getValue(region), damages.pick(random).valueId, ActionChoice.NotChosen, null, true,
                    Dot(p.x / drawing.width, p.y / drawing.height),
                )
                made += Made((result as Ok).value, index)
            }
        }
        // A finding with no dot shows nothing.
        val inspectionWithNone = (repository.addInspection(vehicle, "01/12/2025", "500") as Ok).value
        repository.addFinding(inspectionWithNone, repository.offeredValues(Role.AREA).first().valueId, damages.first().valueId, ActionChoice.NotChosen, null, true)

        launch().use { _ ->
            compose.click("vehicle-row:$vehicle")
            compose.waitForTag("car-drawing")
            val tag = Regex("vehicle-dot:.+")
            compose.waitLabelled("${made.size} vehicle dots (now ${matching(compose, tag)})") { matching(compose, tag) == made.size }
            assertEquals("the Vehicle screen shows every dot and no more", made.size, matching(compose, tag))
            for (m in made) {
                val node = compose.onNodeWithTagNow("vehicle-dot:${m.findingId}")
                val state = node.config.getOrNull(SemanticsProperties.StateDescription)
                assertEquals("dot of ${m.findingId}", if (m.inspectionIndex == dates.lastIndex) "current" else "older", state)
            }
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithTagNow(tag: String) =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().single()
}
