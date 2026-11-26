package com.streamlinemyjob.inspect.ui

import com.streamlinemyjob.inspect.data.actionOf
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamlinemyjob.inspect.core.FindingText
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.data.OfferedValue
import com.streamlinemyjob.inspect.data.Ok
import com.streamlinemyjob.inspect.testdata.RegGenerator
import com.streamlinemyjob.inspect.testdata.Snapshot
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.activate
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.click
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.container
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.random
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.reset
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.launch
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.snapshot
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.typeInto
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForData
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForNoTag
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * S1.11's instrumented tests (designs/stage-1-foundation.md 4.12, 4.16, 4.17): the finding editor,
 * the same-area dialog, "No action", editing and deleting. Values are drawn at random from what the
 * repository offers, never named; items are reached by test tag only.
 */
@RunWith(AndroidJUnit4::class)
class FindingScreensTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun rnd(test: String): Random = random("${FindingScreensTest::class.qualifiedName}.$test")

    private fun <T> List<T>.pick(random: Random): T = this[random.nextInt(size)]

    /** A vehicle with one inspection, made through the repository. Returns the vehicle's and inspection's ids. */
    private fun vehicleWithInspection(random: Random): Pair<String, String> {
        val repository = container.repository()
        val vehicle = (repository.addVehicle(RegGenerator(random).next().typed) as Ok).value
        val inspection = (repository.addInspection(vehicle, "01/10/2026", "${random.nextInt(200_000)}") as Ok).value
        return vehicle to inspection
    }

    private fun openInspection(vehicleId: String, inspectionId: String) {
        compose.click("vehicle-row:$vehicleId")
        compose.click("inspection-row:$inspectionId")
        compose.waitForTag("finding-add")
    }

    private fun pickArea(value: OfferedValue) {
        compose.activate("area-list-tab:${value.listId}")
        compose.activate("value:${value.valueId}")
    }

    private fun areas() = container.repository().offeredValues(Role.AREA)
    private fun damages() = container.repository().offeredValues(Role.DAMAGE)
    private fun actions() = container.repository().offeredValues(Role.ACTION)

    @Test
    fun aSecondFindingOnTheSameAreaNeedsConfirming() {
        val random = rnd("same-area")
        reset()
        val (vehicle, inspection) = vehicleWithInspection(random)
        val area = areas().pick(random)
        val damage1 = damages().pick(random)
        val damage2 = damages().pick(random)
        launch().use {
            openInspection(vehicle, inspection)
            compose.click("finding-add")
            compose.waitForTag("save")
            pickArea(area)
            compose.activate("value:${damage1.valueId}")
            compose.activate("action-none")
            compose.activate("save")
            compose.waitForTag("finding-add")
            compose.waitForData { it.findings.size == 1 }
            val first = snapshot().findings.single()
            compose.waitForTag("finding-row:${first.id}")

            // The same area again: the dialog asks, naming the first finding; nothing is saved yet.
            compose.click("finding-add")
            compose.waitForTag("save")
            pickArea(area)
            compose.activate("value:${damage2.valueId}")
            compose.activate("action-none")
            compose.activate("save")
            compose.waitForTag("warning-dialog")
            assertEquals(1, snapshot().findings.size)

            // Change: back to the editor, still nothing saved.
            compose.click("cancel")
            compose.waitForNoTag("warning-dialog")
            compose.waitForTag("save")
            assertEquals(1, snapshot().findings.size)

            // Save again and add another anyway.
            compose.activate("save")
            compose.waitForTag("warning-dialog")
            compose.click("confirm")
            compose.waitForTag("finding-add")
            compose.waitForData { it.findings.size == 2 }
            val rows = snapshot().findings.sortedBy { it.createdAt }
            assertEquals(listOf(area.valueId, area.valueId), rows.map { it.areaValueId })
            assertEquals(listOf(damage1.valueId, damage2.valueId), rows.map { it.damageValueId })
        }
    }

    @Test
    fun noActionSavesAFindingWithNoActionAndSoDoesLeavingTheActionAlone() {
        val random = rnd("no-action")
        reset()
        val (vehicle, inspection) = vehicleWithInspection(random)
        val areas = areas()
        val area1 = areas.pick(random)
        var area2 = areas.pick(random)
        while (areas.size > 1 && area2.valueId == area1.valueId) area2 = areas.pick(random)
        val damage = damages().pick(random)
        launch().use {
            openInspection(vehicle, inspection)

            // "No action" chosen on purpose.
            compose.click("finding-add")
            compose.waitForTag("save")
            pickArea(area1)
            compose.activate("value:${damage.valueId}")
            compose.activate("action-none")
            compose.activate("save")
            compose.waitForTag("finding-add")
            compose.waitForData { it.findings.size == 1 }

            // The action not touched at all: saved with the action not chosen yet (4.29), not as "No action".
            compose.click("finding-add")
            compose.waitForTag("save")
            pickArea(area2)
            compose.activate("value:${damage.valueId}")
            compose.activate("save")
            compose.waitForTag("finding-add")
            compose.waitForData { it.findings.size == 2 }
            val saved = snapshot().findings
            assertTrue(saved.all { it.actionValueId == null && it.actionName == null })
            assertTrue("chosen on purpose", saved.single { it.areaValueId == area1.valueId }.noAction)
            assertFalse("not chosen yet", saved.single { it.areaValueId == area2.valueId }.noAction)
            assertEquals(setOf(area1.valueId, area2.valueId), saved.map { it.areaValueId }.toSet())
            assertTrue(saved.all { it.damageValueId == damage.valueId })
            for (f in saved) compose.waitForTag("finding-row:${f.id}")
        }
    }

    @Test
    fun tappingTheSelectedActionAgainUnselectsItSoTheActionIsNotChosenYet() {
        val random = rnd("tap-again")
        reset()
        val (vehicle, inspection) = vehicleWithInspection(random)
        val area = areas().pick(random)
        val damage = damages().pick(random)
        val actions = actions()
        // The chip tapped: an action when the list has one, otherwise "No action".
        val chip = if (actions.isEmpty()) "action-none" else "value:${actions.pick(random).valueId}"
        launch().use {
            openInspection(vehicle, inspection)
            compose.click("finding-add")
            compose.waitForTag("save")
            pickArea(area)
            compose.activate("value:${damage.valueId}")
            compose.activate(chip)
            compose.activate(chip)
            compose.activate("save")
            compose.waitForTag("finding-add")
            compose.waitForData { it.findings.size == 1 }
            val saved = snapshot().findings.single()
            assertNull(saved.actionValueId)
            assertFalse(saved.noAction)

            // Open it, choose "No action", leave; open it again: that chip is selected, tap it, save: not chosen yet again.
            compose.click("finding-row:${saved.id}")
            compose.waitForTag("finding-delete")
            compose.activate("action-none")
            compose.activate("save")
            compose.waitForTag("finding-add")
            compose.waitForData { s -> s.findings.single().noAction }
            compose.click("finding-row:${saved.id}")
            compose.waitForTag("finding-delete")
            compose.activate("action-none")
            compose.activate("save")
            compose.waitForTag("finding-add")
            compose.waitForData { s -> !s.findings.single().noAction }
            assertNull(snapshot().findings.single().actionValueId)
        }
    }

    @Test
    fun anActionAndANoteAreSavedAndEditingChangesThemAndDeleteRemovesTheFinding() {
        val random = rnd("edit-delete")
        reset()
        val (vehicle, inspection) = vehicleWithInspection(random)
        val area = areas().pick(random)
        val damages = damages()
        val damage1 = damages.pick(random)
        var damage2 = damages.pick(random)
        while (damages.size > 1 && damage2.valueId == damage1.valueId) damage2 = damages.pick(random)
        val actions = actions()
        launch().use {
            openInspection(vehicle, inspection)
            compose.click("finding-add")
            compose.waitForTag("save")
            pickArea(area)
            compose.activate("value:${damage1.valueId}")
            val action = if (actions.isEmpty()) null else actions.pick(random)
            if (action == null) compose.activate("action-none") else compose.activate("value:${action.valueId}")
            compose.typeInto("note-input", "Scuffed 5 cm\nnear the edge")
            compose.activate("save")
            compose.waitForTag("finding-add")
            compose.waitForData { it.findings.size == 1 }
            val saved = snapshot().findings.single()
            assertEquals(action?.valueId, saved.actionValueId)
            assertEquals(action == null, saved.noAction)
            assertEquals("Scuffed 5 cm\nnear the edge", saved.note)

            // Edit: the editor shows what was saved; change the damage and drop the note.
            compose.click("finding-row:${saved.id}")
            compose.waitForTag("finding-delete")
            compose.activate("value:${damage2.valueId}")
            compose.typeInto("note-input", "")
            compose.activate("save")
            compose.waitForTag("finding-add")
            compose.waitForData { s -> s.findings.single().damageValueId == damage2.valueId }
            val edited = snapshot().findings.single()
            assertEquals(saved.id, edited.id)
            assertEquals(area.valueId, edited.areaValueId)
            assertEquals(action?.valueId, edited.actionValueId)
            assertNull(edited.note)

            // Delete: asks first; Cancel keeps it, Delete removes it.
            compose.click("finding-row:${saved.id}")
            compose.click("finding-delete")
            compose.click("cancel")
            compose.waitForNoTag("cancel")
            assertEquals(1, snapshot().findings.size)
            compose.click("finding-delete")
            compose.click("confirm")
            compose.waitForTag("finding-add")
            compose.waitForData { it.findings.isEmpty() }
            compose.waitForNoTag("finding-row:${saved.id}")
        }
    }

    @Test
    fun aRemovedValueStaysOnItsFindingMarkedAndIsNotOfferedForANewOne() {
        val random = rnd("removed")
        reset()
        val (vehicle, inspection) = vehicleWithInspection(random)
        val repository = container.repository()
        // Make sure there are two damage values, so one can be removed and a finding can keep it.
        val damageList = damages().first().listId
        while (damages().size < 2) {
            repository.addValue(damageList, "Extra ${random.nextInt(100_000)}")
        }
        val area = areas().pick(random)
        val (kept, other) = damages().let { it[0] to it[1] }
        val findingId = (repository.addFinding(inspection, area.valueId, kept.valueId, actionOf(null), null, false) as Ok).value
        assertTrue(repository.removeValue(kept.valueId) is Ok)
        launch().use {
            openInspection(vehicle, inspection)
            val view = repository.resolvedFinding(findingId)!!.view
            assertTrue(view.damageRemoved)
            compose.waitForTag("finding-row:$findingId")

            // The editor offers the removed value first, marked, as the current choice.
            compose.click("finding-row:$findingId")
            compose.waitForTag("value:${kept.valueId}")
            assertEquals(kept.valueId, repository.offeredValues(Role.DAMAGE, kept.valueId).first().valueId)
            assertTrue(FindingText.of(view).contains("${kept.name}${FindingText.REMOVED}"))

            // A new finding is not offered it.
            compose.activate("save") // saved unchanged: its own removed value is allowed (4.13)
            compose.waitForTag("finding-add")
            compose.click("finding-add")
            compose.waitForTag("save")
            compose.waitForNoTag("value:${kept.valueId}")
            compose.waitForTag("value:${other.valueId}")
        }
    }
}
