package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.ActionChoice
import com.streamlinemyjob.inspect.core.Hit
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.testdata.PointSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Dots, taps and region links in the repository (designs/stage-1-foundation.md 4.24, S1.27): the worked
 * examples, `DOT_BAD`, every `resolveTap` answer, and that every refusal leaves the snapshot unchanged.
 * Regions and values are read from the confirmed drawing and link file, never named here.
 */
@RunWith(RobolectricTestRunner::class)
class DotRepositoryTest {
    private val random = TestRandom.forTest(DotRepositoryTest::class.java.name)
    private val drawing = ConfirmedDrawing.drawing
    private val links = ConfirmedDrawing.links.links

    private class Ctx(val t: TestDb, val inspectionId: String, val damageId: String)

    private fun <T> withDb(block: (Ctx) -> T): T = TestDb(ConfirmedSeed.text).use { t ->
        val vehicle = (t.repo.addVehicle("AB12 CDE") as Ok).value
        val inspection = (t.repo.addInspection(vehicle, "01/01/2025", "1000") as Ok).value
        block(Ctx(t, inspection, t.activeValues(Role.DAMAGE).first().id))
    }

    /** A point (fractions) inside [regionId]. */
    private fun dotIn(regionId: String): Dot {
        val p = PointSampler.inside(random, drawing, regionId)
        return Dot(p.x / drawing.width, p.y / drawing.height)
    }

    private fun Ctx.add(area: String, dot: Dot?, confirmed: Boolean = true) =
        t.repo.addFinding(inspectionId, area, damageId, ActionChoice.NotChosen, null, confirmed, dot)

    /** A point in the gap between parts. */
    private fun gapPoint(): Dot {
        for (i in 0..200) for (j in 0..280) {
            if (Hit.resolveDot(drawing, i / 200.0, j / 280.0) == null) return Dot(i / 200.0, j / 280.0)
        }
        error("no gap")
    }

    @Test
    fun aFindingSavesWithItsDot() = withDb { c ->
        val link = links[random.nextInt(links.size)]
        val dot = dotIn(link.region)
        val id = (c.add(link.value, dot) as Ok).value
        val f = c.t.repo.finding(id)!!
        assertEquals(dot.x, f.dotX!!, 0.0)
        assertEquals(dot.y, f.dotY!!, 0.0)
        assertEquals(drawing.id, f.dotDrawing)
        assertEquals(emptyList<Violation>(), c.t.check())
        assertEquals(TapResult.Picked(link.region, link.value), c.t.repo.resolveTap(dot.x, dot.y))
    }

    @Test
    fun aFindingWithNoDotHasNone() = withDb { c ->
        val id = (c.add(links.first().value, null) as Ok).value
        val f = c.t.repo.finding(id)!!
        assertEquals(Triple(null, null, null), Triple(f.dotX, f.dotY, f.dotDrawing))
    }

    @Test
    fun aDotWithAnotherAreaIsRefusedAndNothingChanges() = withDb { c ->
        val link = links.first()
        val other = links.first { it.value != link.value }.value
        val before = c.t.snapshot()
        val r = c.add(other, dotIn(link.region))
        assertEquals(RepoCodes.DOT_AREA_MISMATCH, (r as Refused).code)
        assertEquals(before, c.t.snapshot())
    }

    @Test
    fun aBadDotIsRefusedAndNothingChanges() = withDb { c ->
        val link = links.first()
        val before = c.t.snapshot()
        for (bad in listOf(Dot(-0.01, 0.5), Dot(1.01, 0.5), Dot(0.5, -0.01), Dot(0.5, 1.01), Dot(Double.NaN, 0.5), Dot(0.5, Double.NaN),
            Dot(Double.POSITIVE_INFINITY, 0.5), Dot(0.5, Double.NEGATIVE_INFINITY))) {
            val r = c.add(link.value, bad)
            assertEquals("$bad", RepoCodes.DOT_BAD, (r as Refused).code)
            assertEquals(before, c.t.snapshot())
        }
    }

    @Test
    fun aDotInAGapOrNotLinkedCannotMakeAFinding() = withDb { c ->
        val before = c.t.snapshot()
        assertEquals(RepoCodes.DOT_AREA_MISMATCH, (c.add(links.first().value, gapPoint()) as Refused).code)
        val region = links.first().region
        c.t.exec("UPDATE region_link SET value_id = NULL WHERE region_id = ?", region)
        val after = c.t.snapshot()
        assertEquals(RepoCodes.DOT_AREA_MISMATCH, (c.add(links.first().value, dotIn(region)) as Refused).code)
        assertEquals(after, c.t.snapshot())
        assertNotEquals(before, after)
    }

    @Test
    fun everyTapAnswer() = withDb { c ->
        val repo = c.t.repo
        val a = links[0]
        val b = links[1]
        val d = links[2]
        val dotA = dotIn(a.region)
        assertEquals(TapResult.Picked(a.region, a.value), repo.resolveTap(dotA.x, dotA.y))
        // Not linked.
        c.t.exec("UPDATE region_link SET value_id = NULL WHERE region_id = ?", b.region)
        val dotB = dotIn(b.region)
        assertEquals(TapResult.NotLinked(b.region), repo.resolveTap(dotB.x, dotB.y))
        // Linked to a removed value, and restored.
        val dotD = dotIn(d.region)
        assertTrue(repo.removeValue(d.value) is Ok)
        assertEquals(TapResult.Removed(d.region, d.value), repo.resolveTap(dotD.x, dotD.y))
        assertTrue(repo.restoreValue(d.value) is Ok)
        assertEquals(TapResult.Picked(d.region, d.value), repo.resolveTap(dotD.x, dotD.y))
        // Linked to a value in a removed list.
        val listId = c.t.values().first { it.id == a.value }.listId
        assertTrue(repo.removeAreaList(listId) is Ok)
        assertEquals(TapResult.Removed(a.region, a.value), repo.resolveTap(dotA.x, dotA.y))
        assertTrue(repo.restoreAreaList(listId) is Ok)
        assertEquals(TapResult.Picked(a.region, a.value), repo.resolveTap(dotA.x, dotA.y))
        // Outside: a gap, off the frame, not finite.
        val gap = gapPoint()
        assertEquals(TapResult.Outside, repo.resolveTap(gap.x, gap.y))
        for ((x, y) in listOf(-0.1 to 0.5, 0.5 to 1.1, Double.NaN to 0.5, 0.5 to Double.POSITIVE_INFINITY)) {
            assertEquals(TapResult.Outside, repo.resolveTap(x, y))
        }
        assertEquals(emptyList<Violation>(), c.t.check())
    }

    @Test
    fun relinkingARegionKeepsTheFindingsDotAndItsEditsWork() = withDb { c ->
        val first = links[0]
        val second = links.first { it.value != first.value }
        val dot = dotIn(first.region)
        val id = (c.add(first.value, dot) as Ok).value
        // The owner re-links the region to another value (4.24, example 3).
        c.t.exec("UPDATE region_link SET value_id = ? WHERE region_id = ?", second.value, first.region)
        val f = c.t.repo.finding(id)!!
        assertEquals(first.value, f.areaValueId)
        // Editing the note with the area and dot unchanged saves, and the dot stays.
        val edit = c.t.repo.editFinding(id, first.value, c.damageId, ActionChoice.NotChosen, "new note", true, dot)
        assertTrue(edit.toString(), edit is Ok)
        val kept = c.t.repo.finding(id)!!
        assertEquals(Triple(dot.x, dot.y, drawing.id), Triple(kept.dotX, kept.dotY, kept.dotDrawing))
        assertEquals("new note", kept.note)
        // A new tap at the same place now picks the other value.
        assertEquals(TapResult.Picked(first.region, second.value), c.t.repo.resolveTap(dot.x, dot.y))
        // Saving the same dot with the new value is fine (the area changed, the new rule holds) ...
        assertTrue(c.t.repo.editFinding(id, second.value, c.damageId, ActionChoice.NotChosen, null, true, dot) is Ok)
        // ... and the old value with that dot is refused, leaving the snapshot as it was.
        val before = c.t.snapshot()
        val r = c.t.repo.editFinding(id, first.value, c.damageId, ActionChoice.NotChosen, null, true, dot)
        assertEquals(RepoCodes.DOT_AREA_MISMATCH, (r as Refused).code)
        assertEquals(before, c.t.snapshot())
        assertEquals(emptyList<Violation>(), c.t.check())
    }

    @Test
    fun anEditWithNoDotRemovesIt() = withDb { c ->
        val link = links.first()
        val id = (c.add(link.value, dotIn(link.region)) as Ok).value
        assertTrue(c.t.repo.editFinding(id, link.value, c.damageId, ActionChoice.NotChosen, null, true, null) is Ok)
        val f = c.t.repo.finding(id)!!
        assertEquals(Triple(null, null, null), Triple(f.dotX, f.dotY, f.dotDrawing))
    }

    @Test
    fun aFindingKeepsItsDotWhenItsValueIsRemoved() = withDb { c ->
        val link = links.first()
        val dot = dotIn(link.region)
        val id = (c.add(link.value, dot) as Ok).value
        assertTrue(c.t.repo.removeValue(link.value) is Ok)
        // Area and dot unchanged: the edit is not checked against the removed value's link again (4.13, 4.24).
        assertTrue(c.t.repo.editFinding(id, link.value, c.damageId, ActionChoice.NoAction, "x", true, dot) is Ok)
        assertEquals(dot.x, c.t.repo.finding(id)!!.dotX!!, 0.0)
        assertEquals(emptyList<Violation>(), c.t.check())
    }

    @Test
    fun regionLinksListsEveryRegion() = withDb { c ->
        val all = c.t.repo.regionLinks()
        assertEquals(drawing.regions.map { it.id }.toSet(), all.keys)
        assertEquals(links.associate { it.region to it.value }, all.filterValues { it != null })
        c.t.exec("UPDATE region_link SET value_id = NULL WHERE region_id = ?", links.first().region)
        assertEquals(null, c.t.repo.regionLinks()[links.first().region])
    }
}
