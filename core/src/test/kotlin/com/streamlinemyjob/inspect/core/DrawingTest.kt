package com.streamlinemyjob.inspect.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.streamlinemyjob.inspect.core.DrawingFixtures as F

/** The drawing validator (designs/stage-1-foundation.md 4.20): the confirmed file, and one fixture per `DRAWING_*` code, each rejected with that code and its JSON path. */
class DrawingTest {
    private class Case(val name: String, val code: String, val path: String, val json: String)

    private val bowTie = "[[100,100],[300,300],[300,100],[100,300]]"
    private val touching = "[[100,100],[300,100],[300,300],[200,100],[200,300],[100,300]]" // its 4th edge's corner lies on the 1st edge

    private val cases: List<Case> = listOf(
        Case("not JSON", Codes.DRAWING_JSON, "", "{not json"),
        Case("empty file", Codes.DRAWING_JSON, "", ""),
        Case("wrong format", Codes.DRAWING_FORMAT, "format", F.drawing(format = "\"smj-seed\"")),
        Case("wrong version", Codes.DRAWING_FORMAT, "version", F.drawing(version = "2")),
        Case("version as text", Codes.DRAWING_FORMAT, "version", F.drawing(version = "\"1\"")),
        Case("unknown top field", Codes.DRAWING_FIELD, "colour", F.drawing(extra = ",\"colour\":\"red\"")),
        Case("region without anchor", Codes.DRAWING_FIELD, "regions[0].anchor", """{"format":"smj-drawing","version":1,"id":"t-1","width":1000,"height":1000,"regions":[{"id":"a","points":${F.square(100, 100, 200)}}],"decor":[],"texts":[]}"""),
        Case("unknown region field", Codes.DRAWING_FIELD, "regions[1].name", F.drawing(regions = F.region("a", F.square(100, 100, 200), "[200,200]") + "," + """{"id":"b","name":"x","anchor":[600,200],"points":${F.square(500, 100, 200)}}""")),
        Case("missing texts", Codes.DRAWING_FIELD, "texts", """{"format":"smj-drawing","version":1,"id":"t-1","width":1000,"height":1000,"regions":[],"decor":[]}"""),
        Case("id not a string", Codes.DRAWING_FIELD, "id", F.drawing(id = "7")),
        Case("width too small", Codes.DRAWING_SIZE, "width", F.drawing(width = "99")),
        Case("height too big", Codes.DRAWING_SIZE, "height", F.drawing(height = "10001")),
        Case("width with a fraction", Codes.DRAWING_SIZE, "width", F.drawing(width = "1000.5")),
        Case("width as text", Codes.DRAWING_SIZE, "width", F.drawing(width = "\"1000\"")),
        Case("drawing id with a capital", Codes.DRAWING_ID_BAD, "id", F.drawing(id = "\"Car-1\"")),
        Case("drawing id with a space", Codes.DRAWING_ID_BAD, "id", F.drawing(id = "\"car 1\"")),
        Case("region id with a capital", Codes.DRAWING_ID_BAD, "regions[0].id", F.drawing(regions = F.region("A", F.square(100, 100, 200), "[200,200]"))),
        Case("two regions with one id", Codes.DRAWING_ID_DUP, "regions[1].id", F.drawing(regions = F.region("a", F.square(100, 100, 200), "[200,200]") + "," + F.region("a", F.square(500, 100, 200), "[600,200]"))),
        Case("no regions", Codes.DRAWING_NO_REGIONS, "regions", F.drawing(regions = "")),
        Case("decor missing", Codes.DRAWING_DECOR, "decor", F.drawing(decor = null)),
        Case("decor line of one point", Codes.DRAWING_DECOR, "decor[0].points", F.drawing(decor = """[{"points":[[5,5]],"closed":false}]""")),
        Case("decor coordinate with a fraction", Codes.DRAWING_DECOR, "decor[0].points[1]", F.drawing(decor = """[{"points":[[5,5],[6.5,7]],"closed":false}]""")),
        Case("decor point outside the frame", Codes.DRAWING_DECOR, "decor[1].points[0]", F.drawing(decor = """[{"points":[[5,5],[6,7]],"closed":true},{"points":[[1001,5],[6,7]],"closed":false}]""")),
        Case("decor closed not a boolean", Codes.DRAWING_DECOR, "decor[0].closed", F.drawing(decor = """[{"points":[[5,5],[6,7]],"closed":"yes"}]""")),
        Case("decor closed missing", Codes.DRAWING_DECOR, "decor[0].closed", F.drawing(decor = """[{"points":[[5,5],[6,7]]}]""")),
        Case("region of two points", Codes.DRAWING_POINTS, "regions[0].points", F.drawing(regions = F.region("a", "[[100,100],[300,300]]", "[200,200]"))),
        Case("region coordinate with a fraction", Codes.DRAWING_POINTS, "regions[0].points[1]", F.drawing(regions = F.region("a", "[[100,100],[300.5,100],[300,300],[100,300]]", "[200,200]"))),
        Case("region point outside the frame", Codes.DRAWING_POINTS, "regions[0].points[2]", F.drawing(regions = F.region("a", "[[100,100],[300,100],[1001,300],[100,300]]", "[200,200]"))),
        Case("region point below zero", Codes.DRAWING_POINTS, "regions[0].points[0]", F.drawing(regions = F.region("a", "[[-1,100],[300,100],[300,300],[100,300]]", "[200,200]"))),
        Case("anchor outside the frame", Codes.DRAWING_POINTS, "regions[0].anchor", F.drawing(regions = F.region("a", F.square(100, 100, 200), "[200,1001]"))),
        Case("text position outside the frame", Codes.DRAWING_POINTS, "texts[0].at", F.drawing(texts = """[{"text":"FRONT","at":[500,1001]}]""")),
        Case("point that is not a pair", Codes.DRAWING_POINTS, "regions[0].points[1]", F.drawing(regions = F.region("a", "[[100,100],[300],[300,300],[100,300]]", "[200,200]"))),
        Case("two edges cross", Codes.DRAWING_SELF_CROSS, "regions[0]", F.drawing(regions = F.region("a", bowTie, "[200,200]"))),
        Case("two edges touch", Codes.DRAWING_SELF_CROSS, "regions[0]", F.drawing(regions = F.region("a", touching, "[150,200]"))),
        Case("a wing listed before its alloy", Codes.DRAWING_ANCHOR, "regions[1].anchor", F.drawing(regions = F.region("wing", F.square(100, 100, 400), "[150,150]") + "," + F.region("alloy", F.square(200, 200, 100), "[250,250]"))),
        Case("two regions sharing an edge", Codes.DRAWING_GAP, "regions[1]", F.drawing(regions = F.region("a", F.square(100, 100, 200), "[200,200]") + "," + F.region("b", F.square(300, 100, 200), "[400,200]"))),
        Case("two regions overlapping", Codes.DRAWING_GAP, "regions[1]", F.drawing(regions = F.region("a", F.square(100, 100, 200), "[150,200]") + "," + F.region("b", F.square(250, 100, 200), "[400,200]"))),
        Case("one region inside another", Codes.DRAWING_GAP, "regions[1]", F.drawing(regions = F.region("a", F.square(100, 100, 600), "[150,150]") + "," + F.region("b", F.square(300, 300, 200), "[400,400]"))),
        Case("two regions 31 units apart", Codes.DRAWING_GAP, "regions[1]", F.drawing(regions = F.region("a", F.square(100, 100, 200), "[200,200]") + "," + F.region("b", F.square(331, 100, 200), "[430,200]"))),
        Case("a large region before a small one on top of it", Codes.DRAWING_GAP, "regions[1]", F.drawing(regions = F.region("wing", F.square(100, 100, 400), "[150,150]") + "," + F.region("alloy", F.square(200, 200, 100), "[250,250]"))),
        Case("anchor outside its region", Codes.DRAWING_ANCHOR, "regions[0].anchor", F.drawing(regions = F.region("a", F.square(100, 100, 200), "[400,400]"))),
        Case("region only 30 across", Codes.DRAWING_SMALL, "regions[0]", F.drawing(regions = F.region("a", F.square(100, 100, 30), "[115,115]"))),
        Case("region mostly hidden by an earlier one", Codes.DRAWING_SMALL, "regions[1]", F.drawing(regions = F.region("cover", F.square(100, 100, 200), "[150,200]") + "," + F.region("hidden", "[[100,100],[400,100],[400,300],[100,300]]", "[320,200]").replace("100,100],[400", "100,100],[400"))),
        Case("empty text", Codes.DRAWING_TEXT_EMPTY, "texts[0].text", F.drawing(texts = """[{"text":"   ","at":[500,50]}]""")),
        Case("text of 41 characters", Codes.DRAWING_TEXT_TOO_LONG, "texts[0].text", F.drawing(texts = """[{"text":"${"X".repeat(41)}","at":[500,50]}]""")),
        Case("text with a control character", Codes.DRAWING_TEXT_BAD_CHAR, "texts[0].text", F.drawing(texts = """[{"text":"A\u0001B","at":[500,50]}]""")),
    )

    @Test
    fun theConfirmedDrawingValidates() {
        assertEquals(emptyList<FileError>(), Drawing.validate(F.confirmedDrawingText))
        val d = F.confirmedDrawing
        assertEquals(1000, d.width)
        assertEquals(1400, d.height)
        assertEquals(28, d.regions.size)
        assertEquals(38, d.decor.size)
        assertEquals(4, d.texts.size)
    }

    @Test
    fun twoRegions32UnitsApartAreAccepted() {
        val ok = F.drawing(regions = F.region("a", F.square(100, 100, 200), "[200,200]") + "," + F.region("b", F.square(332, 100, 200), "[430,200]"))
        assertEquals(emptyList<FileError>(), Drawing.validate(ok))
        // Diagonal neighbours: corner to corner, 32 units along the diagonal is not enough but 32 straight across is measured to the edge.
        val corner = F.drawing(regions = F.region("a", F.square(100, 100, 200), "[200,200]") + "," + F.region("b", F.square(320, 320, 200), "[420,420]"))
        assertTrue(Drawing.validate(corner).any { it.code == Codes.DRAWING_GAP }) // 20 across, 20 down: about 28 apart
    }

    @Test
    fun theGapIsReportedOncePerPairWithBothIdsAndTheDistanceRoundedDown() {
        val errors = Drawing.validate(F.drawing(regions = F.region("a", F.square(100, 100, 200), "[200,200]") + "," + F.region("b", F.square(331, 100, 200), "[430,200]"))).filter { it.code == Codes.DRAWING_GAP }
        assertEquals(1, errors.size)
        assertEquals("regions[1]", errors[0].path)
        assertTrue(errors[0].detail, errors[0].detail.startsWith("a and b are 31 units apart"))
    }

    @Test
    fun theVersionTwoDrawingFailsTheGapCheckAndTheConfirmedOneKeepsEveryPairApart() {
        val v2 = javaClass.getResource("/fixtures/car-top-v2.json")!!.readText()
        assertTrue(Drawing.validate(v2).any { it.code == Codes.DRAWING_GAP })
        val d = F.confirmedDrawing
        for (j in d.regions.indices) for (i in 0 until j) {
            assertTrue("${d.regions[i].id} and ${d.regions[j].id}", Drawing.distance(d.regions[i], d.regions[j]) >= Drawing.GAP_MIN)
        }
    }

    @Test
    fun theSmallFixtureBaseValidates() {
        assertEquals(emptyList<FileError>(), Drawing.validate(F.drawing()))
    }

    @Test
    fun everyCodeHasAFixture() {
        assertEquals(Codes.DRAWING_ALL.toSet(), cases.map { it.code }.toSet())
    }

    @Test
    fun eachFixtureIsRejectedWithItsCodeAndPath() {
        for (c in cases) {
            val errors = Drawing.validate(c.json)
            assertTrue("${c.name}: expected ${c.code} ${c.path}, got $errors", errors.any { it.code == c.code && it.path == c.path })
            assertTrue("${c.name}: the drawing must not be valid", Drawing.parse(c.json) is Drawing.Companion.Invalid)
        }
    }

    @Test
    fun aWingAfterItsAlloyHasNoAnchorErrorSoOnlyTheOrderWasWrong() {
        val ok = F.drawing(regions = F.region("alloy", F.square(200, 200, 100), "[250,250]") + "," + F.region("wing", F.square(100, 100, 400), "[150,150]"))
        // The alloy inside the wing still fails DRAWING_GAP; only the anchor error goes away.
        assertEquals(listOf(Codes.DRAWING_GAP), Drawing.validate(ok).map { it.code })
    }

    @Test
    fun aLargeRegionBeforeASmallOneOnTopOfItFailsBothAnchorAndGap() {
        val text = F.drawing(regions = F.region("wing", F.square(100, 100, 400), "[150,150]") + "," + F.region("alloy", F.square(200, 200, 100), "[250,250]"))
        assertEquals(setOf(Codes.DRAWING_ANCHOR, Codes.DRAWING_GAP), Drawing.validate(text).map { it.code }.toSet())
    }

    @Test
    fun allErrorsAreCollectedNotJustTheFirst() {
        val text = F.drawing(id = "\"BAD\"", width = "5", texts = """[{"text":"","at":[1,1]}]""")
        val codes = Drawing.validate(text).map { it.code }
        assertTrue(codes.toString(), codes.containsAll(listOf(Codes.DRAWING_ID_BAD, Codes.DRAWING_SIZE, Codes.DRAWING_TEXT_EMPTY)))
    }

    @Test
    fun errorsPrintAsCodePathAndDetail() {
        assertEquals("DRAWING_SIZE width (expected a whole number from 100 to 10000, found 99)", Drawing.validate(F.drawing(width = "99")).first().toString())
    }

    @Test
    fun randomDrawingsValidate() {
        val r = TestRandom.forTest("${DrawingTest::class.qualifiedName}.random")
        repeat(25) { assertEquals(emptyList<FileError>(), Drawing.validate(randomText(r))) }
    }

    private fun randomText(r: kotlin.random.Random): String {
        // The same generator the Hit tests use, re-serialised by building it again from its own text.
        val d = HitTest.randomDrawing(r)
        val regions = d.regions.joinToString(",") { F.region(it.id, "[" + it.points.joinToString(",") { p -> "[${p.x},${p.y}]" } + "]", "[${it.anchor.x},${it.anchor.y}]") }
        return F.drawing(width = "${d.width}", height = "${d.height}", regions = regions, texts = """[{"text":"X","at":[1,1]}]""")
    }
}
