package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.ActionChoice
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.testdata.DataGenerator
import com.streamlinemyjob.inspect.testdata.LinkGenerator
import com.streamlinemyjob.inspect.testdata.Mutation
import com.streamlinemyjob.inspect.testdata.PointSampler
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Region links in the repository (designs/stage-1-foundation.md 4.24, S1.29): every refusal of `linkRegion` and
 * `unlinkRegion` leaves the snapshot unchanged; worked examples 3 to 5; and, after `Mutation.apply` and
 * `Mutation.relink`, a tap on every region answers as the link row says. Regions and values come from the
 * drawing, the link file and the seed, never from names written here (the worked examples name the confirmed
 * ones, as the design does).
 */
@RunWith(RobolectricTestRunner::class)
class RegionLinksRepositoryTest {
    private val random = TestRandom.forTest(RegionLinksRepositoryTest::class.java.name)
    private val drawing = ConfirmedDrawing.drawing
    private val links = ConfirmedDrawing.links.links

    private fun dotIn(regionId: String): Dot {
        val p = PointSampler.inside(random, drawing, regionId)
        return Dot(p.x / drawing.width, p.y / drawing.height)
    }

    @Test
    fun everyRefusalChangesNothing() = TestDb(ConfirmedSeed.text).use { t ->
        val repo = t.repo
        val region = links.first().region
        val damage = t.activeValues(Role.DAMAGE).first().id
        val action = t.activeValues(Role.ACTION).first().id
        val before = t.snapshot()
        // A region that is not in the drawing.
        for (bad in listOf("no-such-region", "", region.uppercase() + "x")) {
            assertEquals(RepoCodes.REGION_UNKNOWN, (repo.linkRegion(bad, links.first().value) as Refused).code)
            assertEquals(RepoCodes.REGION_UNKNOWN, (repo.unlinkRegion(bad) as Refused).code)
        }
        // A value that is not an area value: damage, action, and one that does not exist.
        for (notArea in listOf(damage, action, "no-such-value")) {
            val r = repo.linkRegion(region, notArea) as Refused
            assertEquals(RepoCodes.ROLE_MISMATCH, r.code)
            assertEquals("area", r.detail)
        }
        // The region is checked before the value.
        assertEquals(RepoCodes.REGION_UNKNOWN, (repo.linkRegion("no-such-region", damage) as Refused).code)
        assertEquals(before, t.snapshot())
        // A removed value, and a value of a removed list.
        val value = links[1].value
        assertTrue(repo.removeValue(value) is Ok)
        val afterRemove = t.snapshot()
        assertEquals(RepoCodes.VALUE_REMOVED, (repo.linkRegion(region, value) as Refused).code)
        val listId = t.values().first { it.id == links[2].value }.listId
        assertTrue(repo.removeAreaList(listId) is Ok)
        val afterList = t.snapshot()
        assertEquals(RepoCodes.VALUE_REMOVED, (repo.linkRegion(region, links[2].value) as Refused).code)
        assertEquals(afterList.copy(), t.snapshot())
        assertTrue(afterRemove != before)
        assertEquals(emptyList<Violation>(), t.check())
    }

    @Test
    fun linkingToTheSameValueAndUnlinkingAnUnlinkedRegionAreOkAndChangeNothing() = TestDb(ConfirmedSeed.text).use { t ->
        val link = links.first()
        val before = t.snapshot()
        assertTrue(t.repo.linkRegion(link.region, link.value) is Ok)
        assertEquals(before, t.snapshot())
        assertTrue(t.repo.unlinkRegion(link.region) is Ok)
        assertEquals(null, t.repo.regionLinks()[link.region])
        val unlinked = t.snapshot()
        assertTrue(t.repo.unlinkRegion(link.region) is Ok)
        assertEquals(unlinked, t.snapshot())
        // Linking again, to another value, takes.
        val other = links.first { it.value != link.value }.value
        assertTrue(t.repo.linkRegion(link.region, other) is Ok)
        assertEquals(other, t.repo.regionLinks()[link.region])
        assertEquals(emptyList<Violation>(), t.check())
    }

    @Test
    fun workedExamples3To5() = TestDb(ConfirmedSeed.text).use { t ->
        val vehicle = (t.repo.addVehicle("AB12 CDE") as Ok).value
        val inspection = (t.repo.addInspection(vehicle, "01/01/2025", "1000") as Ok).value
        val damage = t.activeValues(Role.DAMAGE).first().id
        val door = links.first { it.region == "ds-front-door" }
        val rear = links.first { it.region == "ds-rear-door" }
        val dot = dotIn(door.region)
        val id = (t.repo.addFinding(inspection, door.value, damage, ActionChoice.NotChosen, null, true, dot) as Ok).value
        // 3. Re-link the door to the rear door's value: F3 keeps its area and dot, an edit of its note saves, a new tap picks the new value.
        assertTrue(t.repo.linkRegion(door.region, rear.value) is Ok)
        val f = t.repo.finding(id)!!
        assertEquals(Triple(door.value, dot.x, dot.y), Triple(f.areaValueId, f.dotX, f.dotY))
        assertTrue(t.repo.editFinding(id, door.value, damage, ActionChoice.NotChosen, "note", true, dot) is Ok)
        assertEquals(TapResult.Picked(door.region, rear.value), t.repo.resolveTap(dot.x, dot.y))
        // 4. Remove that value: the same tap gives Removed; restoring it picks again.
        assertTrue(t.repo.removeValue(rear.value) is Ok)
        assertEquals(TapResult.Removed(door.region, rear.value), t.repo.resolveTap(dot.x, dot.y))
        assertTrue(t.repo.restoreValue(rear.value) is Ok)
        assertEquals(TapResult.Picked(door.region, rear.value), t.repo.resolveTap(dot.x, dot.y))
        // 5. Unlink the roof: a tap there is NotLinked, and the roof value is still there to pick from its list.
        val roof = links.first { it.region == "roof" }
        assertTrue(t.repo.unlinkRegion(roof.region) is Ok)
        val roofDot = dotIn(roof.region)
        assertEquals(TapResult.NotLinked(roof.region), t.repo.resolveTap(roofDot.x, roofDot.y))
        assertTrue(t.activeValues(Role.AREA).any { it.id == roof.value })
        assertEquals(emptyList<Violation>(), t.check())
    }

    private fun relinkRun(seedText: String, linksText: String?) = TestDb(seedText, linksText = linksText).use { t ->
        val port = RepositoryAdapter(t.db, t.drawing)
        DataGenerator.generate(random, port, 100, Instant.now().truncatedTo(ChronoUnit.MILLIS), drawing = t.drawing)
        Mutation.apply(random, port)
        val findingsBefore = port.snapshot().findings
        Mutation.relink(random, port)
        val after = port.snapshot()
        // Every finding made before keeps its area value and dot.
        assertEquals(findingsBefore, after.findings)
        val lists = t.lists().associateBy { it.id }
        val values = t.values().associateBy { it.id }
        val rows = after.regionLinks.associate { it.regionId to it.valueId }
        assertEquals(drawing.regions.map { it.id }.toSet(), rows.keys)
        for (region in drawing.regions) {
            repeat(50) {
                val dot = dotIn(region.id)
                val valueId = rows[region.id]
                val expected = when {
                    valueId == null -> TapResult.NotLinked(region.id)
                    values.getValue(valueId).removed || lists.getValue(values.getValue(valueId).listId).removed ->
                        TapResult.Removed(region.id, valueId)
                    else -> TapResult.Picked(region.id, valueId)
                }
                assertEquals(expected, t.repo.resolveTap(dot.x, dot.y))
            }
        }
        // A linked region always links an area value that was offered when relink ran.
        assertTrue(rows.values.filterNotNull().all { lists.getValue(values.getValue(it).listId).role == Role.AREA })
        assertEquals(emptyList<Violation>(), t.check())
    }

    @Test
    fun relinkWithTheConfirmedSeed() = relinkRun(ConfirmedSeed.text, null)

    @Test
    fun relinkWithGeneratedSeedsAndLinks() {
        repeat(3) {
            val seed = SeedGenerator.generate(random, ConfirmedSeed.listNames)
            relinkRun(seed, LinkGenerator.generate(random, seed, drawing))
        }
    }

    @Test
    fun relinkTouchesEveryKindAtLeastOnceOverManyRuns() = TestDb(ConfirmedSeed.text).use { t ->
        val port = RepositoryAdapter(t.db, t.drawing)
        val seen = HashSet<String>()
        repeat(6) {
            val before = port.regionLinks()
            Mutation.relink(random, port)
            val after = port.regionLinks()
            for (r in before.keys) {
                seen += when {
                    before[r] == after[r] -> "same"
                    after[r] == null -> "unlinked"
                    else -> "linked"
                }
            }
        }
        assertEquals(setOf("same", "unlinked", "linked"), seen)
    }
}
