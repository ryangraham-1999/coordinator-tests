package com.streamlinemyjob.inspect.ui

import com.streamlinemyjob.inspect.data.actionOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamlinemyjob.inspect.MainActivity
import com.streamlinemyjob.inspect.TestHooks
import com.streamlinemyjob.inspect.core.FindingText
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Seed
import com.streamlinemyjob.inspect.core.Text
import com.streamlinemyjob.inspect.data.Ok
import com.streamlinemyjob.inspect.data.RepositoryAdapter
import com.streamlinemyjob.inspect.data.SeedLoader
import com.streamlinemyjob.inspect.testdata.Mutation
import com.streamlinemyjob.inspect.testdata.RegGenerator
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import com.streamlinemyjob.inspect.testdata.Snapshot
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.WAIT_MS
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.activate
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.back
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.click
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.container
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.db
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.launch
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.random
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.reset
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.snapshot
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.typeInto
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForData
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * S1.13's instrumented tests (designs/stage-1-foundation.md 4.13, 4.16): the Settings and List editor
 * screens. Each scenario runs twice, with the confirmed seed and with a generated seed plus the
 * standard mutation. Values and lists are drawn at random from what the database holds, never named;
 * items are reached by test tag only.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreensTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private enum class Seeds { CONFIRMED, GENERATED }

    private fun rnd(test: String, seeds: Seeds): Random = random("${SettingsScreensTest::class.qualifiedName}.$test.$seeds")

    private fun bundledSeed(): String = SeedLoader.bundledSeed(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun seed(seeds: Seeds, random: Random) {
        when (seeds) {
            Seeds.CONFIRMED -> reset()
            Seeds.GENERATED -> {
                val confirmedNames = (Seed.parse(bundledSeed()) as Seed.Valid).seed.lists.map { it.name }
                TestHooks.reset(SeedGenerator.generate(random, confirmedNames))
                Mutation.apply(random, RepositoryAdapter(db()))
            }
        }
    }

    private fun both(test: String, body: (Seeds, Random) -> Unit) {
        for (seeds in Seeds.entries) {
            val random = rnd(test, seeds)
            seed(seeds, random)
            body(seeds, random)
        }
    }

    private fun <T> List<T>.pick(random: Random): T = this[random.nextInt(size)]

    private val repository get() = container.repository()

    /** A vehicle with one inspection, through the repository. Returns the vehicle's and inspection's ids. */
    private fun vehicleWithInspection(random: Random): Pair<String, String> {
        val vehicle = (repository.addVehicle(RegGenerator(random).next().typed) as Ok).value
        val inspection = (repository.addInspection(vehicle, "01/10/2026", "${random.nextInt(200_000)}") as Ok).value
        return vehicle to inspection
    }

    /** A name that is clean (saved as typed) and whose key is new to [taken]. */
    private fun freshName(random: Random, taken: Set<String>): String {
        val words = listOf("Renamed", "ñandú", "𝔸rch", "O'Brien", "\"Mint\"", "Zoë", "R&R", "3/4", "kit")
        while (true) {
            val raw = "${words.pick(random)} ${words.pick(random)} ${random.nextInt(100_000)}"
            val name = Text.checkName(raw)
            if (name.problems.isEmpty() && name.cleaned == raw && name.key !in taken) return raw
        }
    }

    private fun keysOf(s: Snapshot, listId: String?): Set<String> =
        if (listId == null) s.lists.map { it.nameKey }.toSet() else s.values.filter { it.listId == listId }.map { it.nameKey }.toSet()

    /**
     * Scrolls the lazy list [scroll] to the row tagged [tag]. The Settings list exists, empty, before its
     * rows are loaded (and a new row appears a moment after the write), and `performScrollToNode` fails
     * at once if the row is not in the list yet, so it is tried again until the row is there. It still
     * fails with the same error if the row never shows up within [WAIT_MS].
     */
    private fun ComposeTestRule.scrollTo(scroll: String, tag: String) {
        waitForTag(scroll)
        var last: AssertionError? = null
        val found = try {
            waitUntil(WAIT_MS) {
                try {
                    onNodeWithTag(scroll).performScrollToNode(hasTestTag(tag))
                    true
                } catch (e: AssertionError) {
                    last = e
                    false
                }
            }
            true
        } catch (e: ComposeTimeoutException) {
            false
        }
        if (!found) throw AssertionError("the row $tag did not show up in $scroll within ${WAIT_MS} ms", last)
        waitForTag(tag)
    }

    private fun openList(listId: String) {
        compose.click("open-settings")
        compose.scrollTo("settings-scroll", "list-row:$listId")
        compose.activate("list-row:$listId")
        compose.waitForTag("list-rename")
    }

    private fun toVehicles(scenario: ActivityScenario<MainActivity>) {
        while (true) {
            if (compose.onAllNodesWithTag("open-settings").fetchSemanticsNodes().isNotEmpty()) return
            back(scenario)
            compose.waitForIdle()
        }
    }

    private fun openInspection(vehicleId: String, inspectionId: String) {
        compose.click("vehicle-row:$vehicleId")
        compose.click("inspection-row:$inspectionId")
        compose.waitForTag("finding-add")
    }

    private fun ComposeTestRule.waitForRowText(findingId: String, expected: String) {
        waitUntil(WAIT_MS) {
            onAllNodes(hasTestTag("finding-row:$findingId") and hasText(expected, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun roleOf(s: Snapshot, valueId: String): Role {
        val listId = s.values.single { it.id == valueId }.listId
        return s.lists.single { it.id == listId }.role
    }

    /** One finding made through the repository from random offered values (an action only if one is offered). */
    private fun addFinding(random: Random, inspectionId: String): String {
        val area = repository.offeredValues(Role.AREA).pick(random)
        val damage = repository.offeredValues(Role.DAMAGE).pick(random)
        val action = repository.offeredValues(Role.ACTION).let { if (it.isEmpty()) null else it.pick(random) }
        return (repository.addFinding(inspectionId, area.valueId, damage.valueId, actionOf(action?.valueId), null, false) as Ok).value
    }

    @Test
    fun renamingAValueShowsTheNewNameOnAnExistingFinding() = both("rename") { _, random ->
        val (vehicle, inspection) = vehicleWithInspection(random)
        val finding = addFinding(random, inspection)
        val row = snapshot().findings.single { it.id == finding }
        val valueId = listOfNotNull(row.areaValueId, row.damageValueId, row.actionValueId).pick(random)
        val before = snapshot()
        val value = before.values.single { it.id == valueId }
        val newName = freshName(random, keysOf(before, value.listId))
        launch().use { scenario ->
            openList(value.listId)
            compose.scrollTo("list-scroll", "value-rename:$valueId")
            compose.activate("value-rename:$valueId")
            compose.typeInto("value-rename-input", newName)
            compose.activate("value-rename:$valueId")
            compose.waitForData { s -> s.values.single { it.id == valueId }.name == newName }
            toVehicles(scenario)
            openInspection(vehicle, inspection)
            val view = repository.resolvedFinding(finding)!!.view
            val shown = FindingText.of(view)
            assertTrue("the finding shows the new name", shown.contains(newName))
            compose.waitForRowText(finding, shown)
        }
    }

    @Test
    fun removingAValueMarksItOnTheFindingHidesItFromNewFindingsAndAddingItsNameRestoresIt() = both("remove") { _, random ->
        val (vehicle, inspection) = vehicleWithInspection(random)
        // Any value the rules let us remove: an action always; damage and area only while another remains.
        val s0 = snapshot()
        val activeAreas = s0.values.filter { v -> !v.removed && s0.lists.single { it.id == v.listId }.let { it.role == Role.AREA && !it.removed } }
        val candidates = repository.offeredValues(Role.ACTION).map { it.valueId } +
            repository.offeredValues(Role.DAMAGE).let { if (it.size > 1) it.map { v -> v.valueId } else emptyList() } +
            (if (activeAreas.size > 1) activeAreas.map { it.id } else emptyList())
        assertTrue("some value can be removed", candidates.isNotEmpty())
        val valueId = candidates.pick(random)
        val value = s0.values.single { it.id == valueId }
        val role = roleOf(s0, valueId)
        // A finding that uses it.
        val area = if (role == Role.AREA) valueId else repository.offeredValues(Role.AREA).pick(random).valueId
        val damage = if (role == Role.DAMAGE) valueId else repository.offeredValues(Role.DAMAGE).pick(random).valueId
        val action = if (role == Role.ACTION) valueId else repository.offeredValues(Role.ACTION).let { if (it.isEmpty()) null else it.pick(random).valueId }
        val finding = (repository.addFinding(inspection, area, damage, actionOf(action), null, false) as Ok).value

        launch().use { scenario ->
            openList(value.listId)
            compose.scrollTo("list-scroll", "value-remove:$valueId")
            compose.activate("value-remove:$valueId")
            compose.waitForData { s -> s.values.single { it.id == valueId }.removed }

            // The finding shows it marked "(removed)".
            toVehicles(scenario)
            openInspection(vehicle, inspection)
            val shown = FindingText.of(repository.resolvedFinding(finding)!!.view)
            assertTrue("the finding marks the removed value: $shown", shown.contains(value.name + FindingText.REMOVED))
            compose.waitForRowText(finding, shown)

            // A new finding is not offered it. (The editor shows the area chips of one list at a time.)
            compose.click("finding-add")
            compose.waitForTag("save")
            assertTrue(repository.offeredValues(role).none { it.valueId == valueId })
            val sameList = repository.offeredValues(role).firstOrNull { it.listId == value.listId }
            if (sameList != null) {
                if (role == Role.AREA) compose.activate("area-list-tab:${value.listId}")
                compose.waitForTag("value:${sameList.valueId}")
                compose.onAllNodesWithTag("value:$valueId").assertCountEquals(0)
            }

            // Adding its name again restores it: no new value, the same id, back among the offered values.
            toVehicles(scenario)
            openList(value.listId)
            val count = snapshot().values.size
            compose.typeInto("value-add-input", value.name)
            compose.activate("value-add")
            compose.waitForData { s -> !s.values.single { it.id == valueId }.removed }
            assertEquals(count, snapshot().values.size)
            assertTrue(repository.offeredValues(role).any { it.valueId == valueId })
            assertFalse(FindingText.of(repository.resolvedFinding(finding)!!.view).contains(FindingText.REMOVED))
        }
    }

    @Test
    fun movingAValueUpOrDownChangesTheOrderInTheFindingEditor() = both("reorder") { _, random ->
        val (vehicle, inspection) = vehicleWithInspection(random)
        val lists = repository.settingsLists().filter { !it.removed && it.activeValues >= 2 }
        assertTrue("some list has two values", lists.isNotEmpty())
        val list = lists.pick(random)
        val order = repository.listDetail(list.id)!!.active.map { it.id }
        val index = random.nextInt(order.size)
        val up = if (index == 0) false else if (index == order.lastIndex) true else random.nextBoolean()
        val moved = order[index]
        val neighbour = order[if (up) index - 1 else index + 1]
        launch().use { scenario ->
            openList(list.id)
            val tag = if (up) "value-up:$moved" else "value-down:$moved"
            compose.scrollTo("list-scroll", tag)
            compose.activate(tag)
            val expected = order.toMutableList().also { it[index] = neighbour; it[if (up) index - 1 else index + 1] = moved }
            compose.waitUntil(WAIT_MS) { repository.listDetail(list.id)!!.active.map { it.id } == expected }

            toVehicles(scenario)
            openInspection(vehicle, inspection)
            compose.click("finding-add")
            compose.waitForTag("save")
            if (list.role == Role.AREA) compose.activate("area-list-tab:${list.id}")
            compose.waitForTag("value:$moved")
            compose.waitForTag("value:$neighbour")
            // The editor's chips in the order it shows them (the semantics tree is in composition order).
            val shown = compose.onAllNodes(SemanticsMatcher("a value chip") { node ->
                node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("value:") == true
            }).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].removePrefix("value:") }
            val expectedOrder = repository.listDetail(list.id)!!.active.map { it.id }
            val inEditor = shown.filter { it in expectedOrder }
            assertEquals("the editor shows the list's values in the repository's order (moved ${if (up) "up" else "down"})", expectedOrder, inEditor)
        }
    }

    @Test
    fun anAreaListWithTwoValuesCanBeUsedInANewFinding() = both("new-area-list") { _, random ->
        val (vehicle, inspection) = vehicleWithInspection(random)
        val before = snapshot()
        val listName = freshName(random, keysOf(before, null))
        val damage = repository.offeredValues(Role.DAMAGE).pick(random)
        val action = repository.offeredValues(Role.ACTION).let { if (it.isEmpty()) null else it.pick(random) }
        launch().use { scenario ->
            compose.click("open-settings")
            compose.typeInto("area-list-add-input", listName)
            compose.activate("area-list-add")
            compose.waitForData { s -> s.lists.any { it.name == listName } }
            val list = snapshot().lists.single { it.name == listName }
            assertEquals(Role.AREA, list.role)
            compose.scrollTo("settings-scroll", "list-row:${list.id}")
            compose.activate("list-row:${list.id}")
            compose.waitForTag("value-add-input")
            val names = mutableListOf<String>()
            for (i in 0 until 2) {
                val n = freshName(random, names.map { Text.checkName(it).key }.toSet())
                names += n
                compose.typeInto("value-add-input", n)
                compose.activate("value-add")
                compose.waitForData { s -> s.values.count { it.listId == list.id } == i + 1 }
            }
            val values = snapshot().values.filter { it.listId == list.id }.sortedBy { it.position }
            assertEquals(names, values.map { it.name })

            toVehicles(scenario)
            openInspection(vehicle, inspection)
            compose.click("finding-add")
            compose.waitForTag("save")
            compose.activate("area-list-tab:${list.id}")
            compose.activate("value:${values[1].id}")
            compose.activate("value:${damage.valueId}")
            if (action == null) compose.activate("action-none") else compose.activate("value:${action.valueId}")
            compose.activate("save")
            compose.waitForTag("finding-add")
            compose.waitForData { s -> s.findings.size == 1 }
            assertEquals(values[1].id, snapshot().findings.single().areaValueId)
        }
    }

    @Test
    fun removingTheLastDamageValueShowsTheErrorText() = both("last-damage") { _, random ->
        val damageList = repository.settingsLists().single { it.role == Role.DAMAGE }
        val ids = repository.listDetail(damageList.id)!!.active.map { it.id }
        val last = ids.pick(random)
        for (id in ids - last) assertTrue(repository.removeValue(id) is Ok)
        val before = snapshot()
        launch().use { _ ->
            openList(damageList.id)
            compose.scrollTo("list-scroll", "value-remove:$last")
            compose.activate("value-remove:$last")
            compose.waitForTag("error")
            compose.onNodeWithTag("error").assertTextEquals(Messages.LAST_DAMAGE_VALUE)
            assertEquals(before, snapshot())
        }
    }

    @Test
    fun aNameAlreadyInTheListShowsItsErrorText() = both("name-taken") { _, random ->
        val list = repository.settingsLists().filter { !it.removed && it.activeValues >= 2 }.pick(random)
        val names = repository.listDetail(list.id)!!.active.map { it.name }
        val taken = names.pick(random)
        val before = snapshot()
        launch().use { _ ->
            openList(list.id)
            compose.typeInto("value-add-input", taken)
            compose.activate("value-add")
            compose.waitForTag("error")
            compose.onNodeWithTag("error").assertTextEquals(Messages.nameTaken(taken))
            assertEquals(before, snapshot())
        }
    }
}
