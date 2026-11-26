package com.streamlinemyjob.inspect.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.IntSize
import androidx.test.platform.app.InstrumentationRegistry
import com.streamlinemyjob.inspect.TestHooks
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Seed
import com.streamlinemyjob.inspect.data.Ok
import com.streamlinemyjob.inspect.data.RepositoryAdapter
import com.streamlinemyjob.inspect.data.SeedLoader
import com.streamlinemyjob.inspect.testdata.LinkGenerator
import com.streamlinemyjob.inspect.testdata.Mutation
import com.streamlinemyjob.inspect.testdata.RegGenerator
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.WAIT_MS
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.activate
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.container
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.db
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForData
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitLabelled
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForTag
import java.util.Random

/**
 * Helpers for the drawing tests (S1.28: `TapPickTest`, `OverviewTest`, `ZoomTest`, and `CreateAndRestartTest`'s tapped
 * finding). Taps are made on the node tagged `car-drawing` at the pixel a drawing point is at, worked out from the
 * zoom and offset the node reports (`DrawingViewKey`), so the same helper serves with and without zoom.
 */
object DrawingTestSupport {
    val drawing get() = container.drawing

    fun bundledSeed(): String = SeedLoader.bundledSeed(InstrumentationRegistry.getInstrumentation().targetContext)

    /**
     * Resets the database: the confirmed seed with the confirmed links, or (when [generated]) a generated seed with generated
     * links (every region linked to a random area value of that seed) and the standard mutation (4.26).
     */
    fun setUp(random: Random, generated: Boolean) {
        if (!generated) {
            TestHooks.reset(bundledSeed())
            return
        }
        val confirmedNames = (Seed.parse(bundledSeed()) as Seed.Valid).seed.lists.map { it.name }
        val seed = SeedGenerator.generate(random, confirmedNames)
        val links = LinkGenerator.generate(random, seed, drawing, LinkGenerator.Extreme.ALL_LINKED)
        TestHooks.reset(seed, links)
        Mutation.apply(random, RepositoryAdapter(db()))
    }

    /** A vehicle with one inspection, made through the repository: its and the inspection's ids. */
    fun vehicleWithInspection(random: Random): Pair<String, String> {
        val repository = container.repository()
        val vehicle = (repository.addVehicle(RegGenerator(random).next().typed) as Ok).value
        val inspection = (repository.addInspection(vehicle, "01/10/2026", "${random.nextInt(200_000)}") as Ok).value
        return vehicle to inspection
    }

    /** Region id to area value id, for the regions that are linked to a value that is offered now. */
    fun linkedAndOffered(): Map<String, String> {
        val repository = container.repository()
        val offered = repository.offeredValues(Role.AREA).map { it.valueId }.toSet()
        return repository.regionLinks().mapNotNull { (region, value) -> if (value != null && value in offered) region to value else null }.toMap()
    }

    /**
     * Whether a node with [tag] is on screen. Looks at the unmerged tree: a clickable row merges its children into one node,
     * so the small dot on a finding row (`finding-dot:<id>`) is only found there.
     */
    fun has(compose: ComposeTestRule, tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    fun matching(compose: ComposeTestRule, regex: Regex): Int = compose.onAllNodes(
        SemanticsMatcher("tag matches $regex") { it.config.getOrNull(SemanticsProperties.TestTag)?.let { tag -> regex.matches(tag) } == true },
    ).fetchSemanticsNodes().size

    /**
     * Scrolls the Inspection screen's list until the node with [tag] is on screen. The list is lazy and its first item is the
     * drawing, so rows below it (and the drawing's markers, once the rows are in view) are not there until scrolled to.
     */
    fun scrollToTag(compose: ComposeTestRule, tag: String) {
        compose.waitLabelled("to scroll to $tag") {
            try {
                compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
                true
            } catch (e: AssertionError) {
                false
            }
        }
    }

    /** Waits until the chip (or any node) with [tag] is selected. */
    fun waitSelected(compose: ComposeTestRule, tag: String) {
        compose.waitLabelled("$tag to be selected") { compose.onAllNodes(hasTestTag(tag) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
    }

    fun isSelectedNow(compose: ComposeTestRule, tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag) and isSelected()).fetchSemanticsNodes().isNotEmpty()

    /** The `drawing-status` line's text now, or null if it is not shown. */
    fun statusText(compose: ComposeTestRule): String? =
        compose.onAllNodes(hasTestTag("drawing-status")).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    fun waitStatus(compose: ComposeTestRule, text: String) {
        compose.waitLabelled("the status line to say \"$text\" (it says \"${statusText(compose)}\")") { statusText(compose) == text }
    }

    /** The canvas's size in pixels. */
    fun canvasSize(compose: ComposeTestRule): IntSize = compose.waitForTag("car-drawing").fetchSemanticsNode().size

    /** The zoom and the offset (pixels) the canvas shows now. */
    class View(val zoom: Double, val offsetX: Double, val offsetY: Double)

    fun view(compose: ComposeTestRule): View {
        val text = compose.waitForTag("car-drawing").fetchSemanticsNode().config[DrawingViewKey]
        val (z, x, y) = text.split(";").map { it.toDouble() }
        return View(z, x, y)
    }

    /** Where the drawing point ([ux], [uy]) is on the canvas now, in the canvas's own pixels. */
    fun pixelOf(compose: ComposeTestRule, ux: Double, uy: Double): Offset {
        val size = canvasSize(compose)
        val v = view(compose)
        val scale = size.width.toDouble() / drawing.width
        return Offset((ux * scale * v.zoom + v.offsetX).toFloat(), (uy * scale * v.zoom + v.offsetY).toFloat())
    }

    fun onCanvas(compose: ComposeTestRule, p: Offset, inset: Float = 8f): Boolean {
        val size = canvasSize(compose)
        return p.x >= inset && p.y >= inset && p.x <= size.width - inset && p.y <= size.height - inset
    }

    /** Taps the drawing point ([ux], [uy]) (drawing units), through the zoom and offset the canvas shows. */
    fun tapUnits(compose: ComposeTestRule, ux: Double, uy: Double) {
        val p = pixelOf(compose, ux, uy)
        compose.waitForTag("car-drawing").performTouchInput { click(p) }
    }

    /**
     * Saves the finding and waits for the Inspection screen; the same-area dialog (4.12) may come up when an area repeats,
     * and is confirmed with "Add another" (the tests make several findings from a few linked areas).
     */
    fun saveAndWait(compose: ComposeTestRule, findingsNow: Int) {
        compose.activate("save")
        compose.waitLabelled("the Inspection screen or the same-area dialog after Save") { has(compose, "finding-add") || has(compose, "warning-dialog") }
        if (has(compose, "warning-dialog")) compose.activate("confirm")
        compose.waitForTag("finding-add")
        compose.waitForData { it.findings.size == findingsNow }
    }
}
