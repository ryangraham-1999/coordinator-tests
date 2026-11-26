package com.streamlinemyjob.inspect.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamlinemyjob.inspect.core.Dates
import com.streamlinemyjob.inspect.core.Mileage
import com.streamlinemyjob.inspect.core.Reg
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.data.Ok
import com.streamlinemyjob.inspect.data.actionOf
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.back
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.click
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.container
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.launch
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.random
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.reset
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.resetAndLaunch
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.snapshot
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.typeInto
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForData
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForNoTag
import com.streamlinemyjob.inspect.ui.ScreenTestSupport.waitForTag
import com.streamlinemyjob.inspect.testdata.RegGenerator
import com.streamlinemyjob.inspect.testdata.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Random

/**
 * S1.10's instrumented tests (designs/stage-1-foundation.md 4.8, 4.9, 4.12, 4.16): vehicles,
 * inspections, the date picker, the lower-mileage dialog and delete with counts. Regs, dates and
 * mileages are generated from the device seed; items are reached by test tag only.
 */
@RunWith(AndroidJUnit4::class)
class VehicleScreensTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun rnd(test: String): Random = random("${VehicleScreensTest::class.qualifiedName}.$test")

    /** A typed form of [key] that differs from the one given: random case, spaces and hyphens inside, padding. */
    private fun retyped(key: String, random: Random): String {
        while (true) {
            val sb = StringBuilder(" ".repeat(random.nextInt(3)))
            key.forEachIndexed { i, c ->
                if (i > 0 && random.nextInt(3) == 0) sb.append(if (random.nextBoolean()) " " else "-")
                sb.append(if (random.nextBoolean()) c.lowercaseChar() else c)
            }
            val typed = sb.append(" ".repeat(random.nextInt(3))).toString()
            if (typed.length <= 20) return typed // 4.8: raw input over 20 characters is REG_TOO_LONG
        }
    }

    private fun typedDate(d: LocalDate, random: Random): String =
        if (random.nextBoolean()) Dates.format(d) else "${d.dayOfMonth}/${d.monthValue}/${d.year}"

    private fun typedMileage(miles: Int, random: Random): String =
        if (random.nextBoolean()) miles.toString() else Mileage.grouped(miles)

    private fun addVehicle(typed: String): String = (container.repository().addVehicle(typed) as Ok).value

    private fun addInspection(vehicleId: String, date: LocalDate, miles: Int): String =
        (container.repository().addInspection(vehicleId, Dates.format(date), miles.toString()) as Ok).value

    @Test
    fun createAVehicleFromAGeneratedMessyRegAndFindItByAPartOfItsKey() {
        val random = rnd("create")
        val regs = RegGenerator(random)
        resetAndLaunch().use { scenario ->
            val reg = regs.next()
            compose.click("vehicle-new")
            compose.typeInto("reg-input", reg.typed)
            compose.click("save")
            compose.waitForTag("vehicle-edit")
            compose.waitForData { s -> s.vehicles.any { it.regKey == reg.key } }
            val saved = snapshot().vehicles.single()
            assertEquals(reg.key, saved.regKey)
            assertEquals((Reg.check(reg.typed) as Reg.Ok).display, saved.regDisplay)

            // A part of the key, typed messily; another vehicle whose key does not contain it.
            val start = random.nextInt(reg.key.length)
            val part = reg.key.substring(start, start + 1 + random.nextInt(reg.key.length - start))
            var other = regs.next()
            while (other.key.contains(part)) other = regs.next()
            val otherId = addVehicle(other.typed)

            back(scenario)
            compose.typeInto("vehicle-search", retyped(part, random))
            compose.waitForTag("vehicle-row:${saved.id}")
            compose.waitForNoTag("vehicle-row:$otherId")
            compose.waitForNoTag("vehicle-add-from-search")

            // A valid reg that matches nothing is offered as "Add <REG>".
            var unused = regs.next()
            while (unused.key.contains(part) || reg.key.contains(unused.key) || other.key.contains(unused.key)) unused = regs.next()
            compose.typeInto("vehicle-search", unused.typed)
            compose.waitForNoTag("vehicle-row:${saved.id}")
            compose.click("vehicle-add-from-search")
            compose.waitForTag("vehicle-edit")
            compose.waitForData { s -> s.vehicles.any { it.regKey == unused.key } }
            assertEquals(3, snapshot().vehicles.size)
        }
    }

    @Test
    fun regTakenOffersToOpenTheExistingVehicle() {
        val random = rnd("taken")
        reset()
        val reg = RegGenerator(random).next()
        val vehicleId = addVehicle(reg.typed)
        val inspectionId = addInspection(vehicleId, LocalDate.now().minusDays(random.nextInt(400).toLong()), random.nextInt(200_000))
        val before = snapshot()
        launch().use {

            compose.click("vehicle-new")
            compose.typeInto("reg-input", retyped(reg.key, random))
            compose.click("save")
            compose.waitForTag("error")
            Snapshot.assertSame(before, snapshot(), "a REG_TAKEN save changed the database")
            compose.click("open-existing")
            compose.waitForTag("inspection-row:$inspectionId")
            compose.onNodeWithTag("vehicle-edit").assert(isEnabled())
            Snapshot.assertSame(before, snapshot())
        }
    }

    @Test
    fun inspectionsWithTypedDatesTodayALeapDayAndAPickedDate() {
        val random = rnd("dates")
        reset()
        val vehicleId = addVehicle(RegGenerator(random).next().typed)
        launch().use { scenario ->
            val today = LocalDate.now()
            var leapYear = today.year
            while (!LocalDate.of(leapYear, 1, 1).isLeapYear || LocalDate.of(leapYear, 2, 29).isAfter(today)) leapYear--
            val leapDay = LocalDate.of(leapYear, 2, 29)
            // Rising mileages in the order entered, so no entry is lower than any earlier one.
            val miles = (0 until 3).map { random.nextInt(300_000) }.sorted()

            compose.click("vehicle-row:$vehicleId")

            // Today, typed (the form starts at today; it is typed again in a random form).
            compose.click("inspection-new")
            compose.typeInto("date-input", typedDate(today, random))
            compose.typeInto("mileage-input", typedMileage(miles[0], random))
            compose.click("save")
            compose.waitForTag("inspection-edit")
            back(scenario)

            // A leap day, typed.
            compose.click("inspection-new")
            compose.typeInto("date-input", typedDate(leapDay, random))
            compose.typeInto("mileage-input", typedMileage(miles[1], random))
            compose.click("save")
            compose.waitForTag("inspection-edit")
            back(scenario)

            // Picked: the calendar opens on today's month; pick its first day.
            compose.click("inspection-new")
            compose.click("date-picker-open")
            compose.waitUntil(ScreenTestSupport.WAIT_MS) {
                compose.onAllNodes(isSelectable() and isEnabled() and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodes(isSelectable() and isEnabled() and hasAnyAncestor(isDialog()))[0].performClick()
            compose.click("confirm")
            compose.waitForNoTag("confirm")
            compose.typeInto("mileage-input", typedMileage(miles[2], random))
            compose.click("save")
            compose.waitForTag("inspection-edit")
            back(scenario)

            val firstOfMonth = today.withDayOfMonth(1)
            compose.waitForData { s -> s.inspections.size == 3 }
            val rows = snapshot().inspections.sortedBy { it.createdAt }
            assertEquals(listOf(today, leapDay, firstOfMonth).map { Dates.toStored(it) }, rows.map { it.date })
            assertEquals(miles, rows.map { it.mileage })
            assertTrue(rows.all { it.vehicleId == vehicleId })
            for (row in rows) compose.waitForTag("inspection-row:${row.id}")
        }
    }

    @Test
    fun aLowerMileageShowsTheDialogAndSavesOnlyOnConfirm() {
        val random = rnd("lower")
        reset()
        val vehicleId = addVehicle(RegGenerator(random).next().typed)
        val previousDate = LocalDate.now().minusDays(1L + random.nextInt(300))
        val previousMiles = 1 + random.nextInt(999_999)
        addInspection(vehicleId, previousDate, previousMiles)
        val lower = random.nextInt(previousMiles)
        val before = snapshot()
        launch().use {
            compose.click("vehicle-row:$vehicleId")
            compose.click("inspection-new")
            compose.typeInto("mileage-input", typedMileage(lower, random))
            compose.click("save")
            val dialog = compose.waitForTag("warning-dialog")
            dialog.assert(hasAnyDescendant(hasText(Mileage.grouped(lower), substring = true)))
            dialog.assert(hasAnyDescendant(hasText(Mileage.grouped(previousMiles), substring = true)))
            dialog.assert(hasAnyDescendant(hasText(Dates.format(previousDate), substring = true)))
            Snapshot.assertSame(before, snapshot(), "the warning saved before it was confirmed")

            // Change: nothing is saved, the form stays.
            compose.click("cancel")
            compose.waitForNoTag("warning-dialog")
            Snapshot.assertSame(before, snapshot(), "Change saved the inspection")

            // Save again, then Save on the dialog.
            compose.click("save")
            compose.waitForTag("warning-dialog")
            compose.click("confirm")
            compose.waitForTag("inspection-edit")
            compose.waitForData { s -> s.inspections.size == 2 }
            val added = snapshot().inspections.single { it.mileage == lower && it.date == Dates.toStored(LocalDate.now()) }
            assertEquals(vehicleId, added.vehicleId)
        }
    }

    @Test
    fun deleteAVehicleShowsTheCountsAndCascades() {
        val random = rnd("delete")
        reset()
        run {
            val regs = RegGenerator(random)
            val vehicleId = addVehicle(regs.next().typed)
            val keptId = addVehicle(regs.next().typed)
            val s0 = snapshot()
            val activeLists = s0.lists.filter { !it.removed }.associateBy { it.id }
            fun active(role: Role) = s0.values.filter { v -> !v.removed && activeLists[v.listId]?.role == role }
            val areas = active(Role.AREA)
            val damages = active(Role.DAMAGE)
            val actions = active(Role.ACTION)

            val inspectionCount = 1 + random.nextInt(3)
            var findingCount = 0
            for (k in 0 until inspectionCount) {
                val inspectionId = addInspection(vehicleId, LocalDate.now().minusDays((10 * (inspectionCount - k)).toLong()), 1000 * k)
                val picked = areas.shuffled(random).take(random.nextInt(minOf(4, areas.size) + 1))
                for (area in picked) {
                    val action = if (random.nextBoolean()) actions[random.nextInt(actions.size)].id else null
                    val r = container.repository().addFinding(inspectionId, area.id, damages[random.nextInt(damages.size)].id, actionOf(action), "", false)
                    assertTrue(r.toString(), r is Ok)
                    findingCount++
                }
            }
            val keptInspection = addInspection(keptId, LocalDate.now(), random.nextInt(1000))
            val before = snapshot()

            launch().use {
            compose.click("vehicle-row:$vehicleId")
            compose.click("vehicle-delete")
            compose.waitForTag("confirm")
            val dialog = compose.onNode(isDialog())
            dialog.assert(hasAnyDescendant(hasText("$inspectionCount inspection", substring = true)))
            dialog.assert(hasAnyDescendant(hasText("$findingCount finding", substring = true)))

            compose.click("cancel")
            compose.waitForNoTag("confirm")
            Snapshot.assertSame(before, snapshot(), "Cancel deleted something")

            compose.click("vehicle-delete")
            compose.click("confirm")
            compose.waitForTag("vehicle-search")
            compose.waitForNoTag("vehicle-row:$vehicleId")
            compose.waitForTag("vehicle-row:$keptId")
            val after = snapshot()
            val deletedInspections = before.inspections.filter { it.vehicleId == vehicleId }.map { it.id }.toSet()
            assertEquals(inspectionCount, deletedInspections.size)
            val expected = before.copy(
                vehicles = before.vehicles.filter { it.id != vehicleId },
                inspections = before.inspections.filter { it.vehicleId != vehicleId },
                findings = before.findings.filter { it.inspectionId !in deletedInspections },
            )
            Snapshot.assertSame(expected, after, "the delete did not cascade exactly")
            assertEquals(listOf(keptInspection), after.inspections.map { it.id })
            assertTrue(after.findings.isEmpty())
            }
        }
    }
}
