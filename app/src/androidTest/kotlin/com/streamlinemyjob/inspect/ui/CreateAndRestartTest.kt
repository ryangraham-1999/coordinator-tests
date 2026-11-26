package com.streamlinemyjob.inspect.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamlinemyjob.inspect.TestHooks
import com.streamlinemyjob.inspect.core.Dates
import com.streamlinemyjob.inspect.core.Mileage
import com.streamlinemyjob.inspect.core.Reg
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.data.SeedLoader
import com.streamlinemyjob.inspect.testdata.DrawingPoint
import com.streamlinemyjob.inspect.testdata.PointSampler
import com.streamlinemyjob.inspect.testdata.RegGenerator
import com.streamlinemyjob.inspect.testdata.Snapshot
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.activate
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.back
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.click
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.container
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.db
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.launch
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.random
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.snapshot
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.typeInto
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForData
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Random
import kotlin.math.abs

/**
 * `CreateAndRestartTest` (designs/stage-1-foundation.md 4.16, "Restart test"), run twice: once with the
 * confirmed seed, once with a generated seed plus the standard mutation. Resets; launches; creates a
 * vehicle from `RegGenerator`; creates an inspection with a generated date and mileage; adds 3
 * findings through the editor, each with a random area list, area, damage and action taken from
 * `offeredValues` (one with "No action") and a generated note; closes the activity; reopens the
 * database from its file; launches again; and checks the vehicle, inspection and all 3 findings, both
 * against what was entered and against the snapshot taken before the restart.
 */
@RunWith(AndroidJUnit4::class)
class CreateAndRestartTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private class Entered(val areaValueId: String, val damageValueId: String, val actionValueId: String?, val noAction: Boolean, val note: String)

    @Test
    fun withTheConfirmedSeed() {
        val random = random("${CreateAndRestartTest::class.qualifiedName}.confirmed")
        TestHooks.reset(bundledSeed())
        run(random)
    }

    @Test
    fun withAGeneratedSeedAndTheMutation() {
        val random = random("${CreateAndRestartTest::class.qualifiedName}.generated")
        // Generated links too (4.26), so a tap on the drawing can pick an area of the generated seed.
        DrawingTestSupport.setUp(random, generated = true)
        run(random)
    }

    private fun bundledSeed(): String = SeedLoader.bundledSeed(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun <T> List<T>.pick(random: Random): T = this[random.nextInt(size)]

    private fun run(random: Random) {
        val repository = container.repository()
        val offeredAtStart = Role.entries.flatMap { repository.offeredValues(it) }
        val tappable = DrawingTestSupport.linkedAndOffered()
        val reg = RegGenerator(random).next()
        val inspectionDate = LocalDate.now().minusDays(random.nextInt(400).toLong())
        val miles = random.nextInt(300_000)
        val entered = mutableListOf<Entered>()

        launch().use { scenario ->
            // The vehicle.
            compose.click("vehicle-new")
            compose.typeInto("reg-input", reg.typed)
            compose.click("save")
            compose.waitForTag("vehicle-edit")
            compose.waitForData { s -> s.vehicles.any { it.regKey == reg.key } }

            // The inspection, with a generated date and mileage.
            compose.click("inspection-new")
            compose.typeInto("date-input", if (random.nextBoolean()) Dates.format(inspectionDate) else "${inspectionDate.dayOfMonth}/${inspectionDate.monthValue}/${inspectionDate.year}")
            compose.typeInto("mileage-input", if (random.nextBoolean()) miles.toString() else Mileage.grouped(miles))
            compose.click("save")
            compose.waitForTag("finding-add")
            compose.waitForData { s -> s.inspections.size == 1 }

            // Three findings; each from a random area list, with different areas, so the same-area dialog does not come up.
            // One of each action state (4.29), in a random order: an action, "No action", not chosen yet.
            val states = (0 until 3).toMutableList().also { it.shuffle(random) }
            val areas = repository.offeredValues(Role.AREA)
            val areaLists = areas.map { it.listId }.distinct()
            val usedAreas = HashSet<String>()
            // The first finding is made by a tap on the drawing (S1.28): a random part linked to an offered area.
            val tappedRegion = tappable.keys.toList().pick(random)
            var tappedDot: DrawingPoint? = null
            var canvasPx: androidx.compose.ui.unit.IntSize? = null
            for (i in 0 until 3) {
                var area = areas.filter { it.listId == areaLists.pick(random) }.pick(random)
                while (area.valueId in usedAreas) area = areas.pick(random)
                if (i == 0) area = areas.first { it.valueId == tappable.getValue(tappedRegion) }
                usedAreas += area.valueId
                val damage = repository.offeredValues(Role.DAMAGE).pick(random)
                val actionOffers = repository.offeredValues(Role.ACTION)
                // State 0 is an action (or "No action" if the list is empty), 1 is "No action", 2 is left alone.
                val state = if (states[i] == 0 && actionOffers.isEmpty()) 1 else states[i]
                val action = if (state == 0) actionOffers.pick(random) else null
                val note = note(random)

                compose.click("finding-add")
                compose.waitForTag("save")
                if (i == 0) {
                    val p = PointSampler.inside(random, DrawingTestSupport.drawing, tappedRegion)
                    tappedDot = p
                    canvasPx = DrawingTestSupport.canvasSize(compose)
                    DrawingTestSupport.tapUnits(compose, p.x, p.y)
                    DrawingTestSupport.waitSelected(compose, "value:${area.valueId}")
                } else {
                    compose.activate("area-list-tab:${area.listId}")
                    compose.activate("value:${area.valueId}")
                }
                compose.activate("value:${damage.valueId}")
                when (state) {
                    0 -> compose.activate("value:${action!!.valueId}")
                    1 -> compose.activate("action-none")
                }
                compose.typeInto("note-input", note)
                compose.activate("save")
                compose.waitForTag("finding-add")
                compose.waitForData { s -> s.findings.size == i + 1 }
                entered += Entered(area.valueId, damage.valueId, action?.valueId, state == 1, note)
            }

            // What the database holds now, against what was entered.
            val before = snapshot()
            checkAgainstEntered(before, reg.key, inspectionDate, miles, entered)
            // The finding made by a tap has its dot, within one pixel of the tap (4.25).
            val dotted = before.findings.filter { it.dotX != null }
            assertEquals(1, dotted.size)
            val tapPoint = tappedDot!!
            assertTrue(abs(dotted.single().dotX!! - tapPoint.x / DrawingTestSupport.drawing.width) <= 1.0 / canvasPx!!.width)
            assertTrue(abs(dotted.single().dotY!! - tapPoint.y / DrawingTestSupport.drawing.height) <= 1.0 / canvasPx!!.height)
            assertEquals(tappedRegion, com.streamlinemyjob.inspect.core.Hit.resolveDot(DrawingTestSupport.drawing, dotted.single().dotX!!, dotted.single().dotY!!))
            val findingIds = before.findings.map { it.id }
            for (id in findingIds) DrawingTestSupport.scrollToTag(compose, "finding-row:$id")

            // Restart: close the activity, close the database, open it again from its file.
            scenario.close()
            TestHooks.reopen()
            assertTrue(db().isOpen)
            Snapshot.assertSame(before, snapshot(), "the database after the restart differs from before it")
            checkAgainstEntered(snapshot(), reg.key, inspectionDate, miles, entered)
        }

        // Launch again: the vehicle, its inspection and the three findings are on the screens.
        val after = snapshot()
        launch().use { _ ->
            compose.click("vehicle-row:${after.vehicles.single().id}")
            compose.click("inspection-row:${after.inspections.single().id}")
            for (finding in after.findings) DrawingTestSupport.scrollToTag(compose, "finding-row:${finding.id}")
            compose.waitForTag("finding-add")
        }

        val k = entered.flatMap { listOfNotNull(it.areaValueId, it.damageValueId, it.actionValueId) }.toSet().size
        println("ui sampled $k of ${offeredAtStart.size} values")
    }

    private fun checkAgainstEntered(s: Snapshot, regKey: String, date: LocalDate, miles: Int, entered: List<Entered>) {
        val vehicle = s.vehicles.single()
        assertEquals(regKey, vehicle.regKey)
        val inspection = s.inspections.single()
        assertEquals(vehicle.id, inspection.vehicleId)
        assertEquals(Dates.toStored(date), inspection.date)
        assertEquals(miles, inspection.mileage)
        assertEquals(entered.size, s.findings.size)
        assertTrue(s.findings.all { it.inspectionId == inspection.id })
        val expected = entered.map { listOf(it.areaValueId, it.damageValueId, it.actionValueId, it.noAction, it.note) }.sortedBy { it.toString() }
        val actual = s.findings.map { listOf(it.areaValueId, it.damageValueId, it.actionValueId, it.noAction, it.note) }.sortedBy { it.toString() }
        assertEquals(expected, actual)
        // Each finding's names are those of the values that were picked.
        for (f in s.findings) {
            assertEquals(s.values.single { it.id == f.areaValueId }.name, f.areaName)
            assertEquals(s.values.single { it.id == f.damageValueId }.name, f.damageName)
            assertEquals(f.actionValueId?.let { id -> s.values.single { it.id == id }.name }, f.actionName)
        }
    }

    /** A clean note (no padding or double spaces, so it is saved as typed), 1 to 120 code points, sometimes over two lines. */
    private fun note(random: Random): String {
        val words = listOf("scuffed", "dent", "ñandú", "𝔸rch", "5cm", "O'Brien", "\"mint\"", "edge", "near", "valve", "😀", "&", "chip")
        val line = { (1 + random.nextInt(6)).let { n -> (0 until n).joinToString(" ") { words.pick(random) } } }
        val text = if (random.nextInt(3) == 0) line() + "\n" + line() else line()
        return text.take(120).trim()
    }
}
