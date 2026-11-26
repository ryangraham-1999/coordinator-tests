package com.streamlinemyjob.inspect.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.testdata.PointSampler
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.drawing
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.has
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.isSelectedNow
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.tapUnits
import com.streamlinemyjob.inspect.ui.DrawingTestSupport.waitSelected
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.activate
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.back
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.click
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.container
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.launch
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.random
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.snapshot
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForNoTag
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
 * S1.29's `RegionLinksTest` (designs/stage-1-foundation.md 4.25, S1.29 "Done when"), with the confirmed seed and links and
 * with a generated seed, generated links and `Mutation.apply`: open Settings, then Car drawing; tap a random region; change
 * its link to a random offered value (the screen says so); open a new finding and tap a point in the same region, and that
 * value's chip is selected; unlink a region, and a tap on it in the finding editor leaves the area as it was. Also the
 * missed-tap warning on this screen.
 */
@RunWith(AndroidJUnit4::class)
class RegionLinksTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun rnd(test: String): Random = random("${RegionLinksTest::class.qualifiedName}.$test")

    @Test
    fun withTheConfirmedSeedAndLinks() = run(rnd("confirmed"), generated = false)

    @Test
    fun withAGeneratedSeedGeneratedLinksAndTheMutation() = run(rnd("generated"), generated = true)

    private fun text(tag: String): String? = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun run(random: Random, generated: Boolean) {
        DrawingTestSupport.setUp(random, generated)
        val repository = container.repository()
        val (vehicleId, inspectionId) = DrawingTestSupport.vehicleWithInspection(random)
        val regions = drawing.regions.map { it.id }.shuffled(random)
        val region = regions[0]
        val toUnlink = regions[1]
        val newValue = repository.offeredValues(Role.AREA).let { it[random.nextInt(it.size)] }

        launch().use { scenario ->
            compose.click("open-settings")
            compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("open-region-links"))
            compose.activate("open-region-links")
            compose.waitForTag("car-drawing")
            assertFalse(has(compose, "link-change"))

            // A tap on no region: the missed-tap warning, no selection.
            tapUnits(compose, 500.0, 60.0)
            compose.waitForTag("drawing-missed")
            assertEquals(DrawingTexts.MISSED, text("drawing-status"))
            assertFalse(has(compose, "link-current"))

            // A tap on a region: it says what the region picks; Change opens the picker; a pick links at once.
            val p = PointSampler.inside(random, drawing, region)
            tapUnits(compose, p.x, p.y)
            compose.waitForNoTag("drawing-missed")
            compose.waitForTag("link-current")
            compose.waitLabelled("the link text") { text("link-current")?.let { it == "Not linked" || it.startsWith("Linked to: ") } == true }
            compose.activate("link-change")
            compose.activate("area-list-tab:${newValue.listId}")
            compose.activate("value:${newValue.valueId}")
            val label = repository.areaLabel(newValue.valueId)!!
            compose.waitLabelled("link-current to say $label") { text("link-current") == "Linked to: $label" }
            assertEquals(newValue.valueId, repository.regionLinks()[region])
            assertTrue(has(compose, "link-unlink"))

            // Unlink another region: the text says so, and Unlink goes.
            val q = PointSampler.inside(random, drawing, toUnlink)
            tapUnits(compose, q.x, q.y)
            compose.waitLabelled("the other region's link") { text("link-current") != "Linked to: $label" && text("link-current") != null }
            if (repository.regionLinks()[toUnlink] != null) {
                compose.activate("link-unlink")
                compose.waitLabelled("Not linked") { text("link-current") == "Not linked" }
            }
            assertEquals(null, repository.regionLinks()[toUnlink])
            assertFalse(has(compose, "link-unlink"))

            // A new finding: a tap in the re-linked region picks the new value; a tap in the unlinked region changes nothing.
            back(scenario)
            back(scenario)
            compose.click("vehicle-row:$vehicleId")
            compose.click("inspection-row:$inspectionId")
            compose.waitForTag("finding-add")
            val before = snapshot()
            compose.click("finding-add")
            compose.waitForTag("save")
            tapUnits(compose, p.x, p.y)
            waitSelected(compose, "value:${newValue.valueId}")
            val other = repository.offeredValues(Role.AREA).first { it.valueId != newValue.valueId }
            compose.activate("area-list-tab:${other.listId}")
            compose.activate("value:${other.valueId}")
            waitSelected(compose, "value:${other.valueId}")
            tapUnits(compose, q.x, q.y)
            compose.waitLabelled("the not-linked line") { text("drawing-status") == DrawingTexts.NOT_LINKED_EDITOR }
            assertTrue(isSelectedNow(compose, "value:${other.valueId}"))
            back(scenario)
            compose.waitForTag("finding-add")
            com.streamlinemyjob.inspect.testdata.Snapshot.assertSame(before, snapshot(), "linking, then tapping, changed findings")
        }
    }
}
