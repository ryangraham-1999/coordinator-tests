package com.streamlinemyjob.inspect.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.streamlinemyjob.inspect.core.DrawingFixtures as F

/** The link-file validator (designs/stage-1-foundation.md 4.22): the confirmed file, the worked examples, and one fixture per `LINKS_*` code. */
class LinksTest {
    private val seed get() = F.confirmedSeed
    private val drawing get() = F.confirmedDrawing

    private fun valueIdOf(role: Role): String = seed.lists.first { it.role == role }.values.first().id
    private fun areaValueId(): String = valueIdOf(Role.AREA)
    private fun regionId(n: Int = 0): String = drawing.regions[n].id

    private fun file(
        format: String = "\"smj-links\"",
        version: String = "1",
        drawingId: String = "\"${drawing.id}\"",
        links: String,
        extra: String = "",
    ) = """{"format":$format,"version":$version,"drawing":$drawingId,"links":[$links]$extra}"""

    private fun entry(region: String, value: String) = """{"region":"$region","value":"$value"}"""

    private class Case(val name: String, val code: String, val path: String, val text: String)

    private val cases: List<Case>
        get() {
            val area = areaValueId()
            val r0 = regionId(0)
            val r1 = regionId(1)
            return listOf(
                Case("not JSON", Codes.LINKS_JSON, "", "{nope"),
                Case("wrong format", Codes.LINKS_FORMAT, "format", file(format = "\"smj-seed\"", links = "")),
                Case("wrong version", Codes.LINKS_FORMAT, "version", file(version = "2", links = "")),
                Case("unknown top field", Codes.LINKS_FIELD, "note", file(links = "", extra = ",\"note\":1")),
                Case("entry without a value", Codes.LINKS_FIELD, "links[0].value", file(links = """{"region":"$r0"}""")),
                Case("unknown entry field", Codes.LINKS_FIELD, "links[0].colour", file(links = """{"region":"$r0","value":"$area","colour":"red"}""")),
                Case("missing links", Codes.LINKS_FIELD, "links", """{"format":"smj-links","version":1,"drawing":"${drawing.id}"}"""),
                Case("the wrong drawing", Codes.LINKS_DRAWING, "drawing", file(drawingId = "\"other-drawing\"", links = entry(r0, area))),
                Case("a region that is not in the drawing", Codes.LINKS_REGION_UNKNOWN, "links[1].region", file(links = entry(r0, area) + "," + entry("no-such-region", area))),
                Case("two entries for one region", Codes.LINKS_REGION_DUP, "links[1].region", file(links = entry(r0, area) + "," + entry(r0, area))),
                Case("a value that is not in the seed", Codes.LINKS_VALUE_UNKNOWN, "links[0].value", file(links = entry(r0, "no-such-value"))),
                Case("a damage value", Codes.LINKS_VALUE_ROLE, "links[1].value", file(links = entry(r0, area) + "," + entry(r1, valueIdOf(Role.DAMAGE)))),
                Case("an action value", Codes.LINKS_VALUE_ROLE, "links[0].value", file(links = entry(r0, valueIdOf(Role.ACTION)))),
            )
        }

    @Test
    fun theConfirmedLinkFileValidates() {
        assertEquals(emptyList<FileError>(), Links.validate(F.confirmedLinksText, seed, drawing))
        val parsed = Links.parse(F.confirmedLinksText, seed, drawing) as Links.Valid
        assertEquals(drawing.id, parsed.links.drawing)
        // Read from the file itself: one entry per link in the text.
        val count = Json.parseToJsonElement(F.confirmedLinksText).jsonObject["links"]!!.jsonArray.size
        assertEquals(count, parsed.links.links.size)
        assertEquals(28, count) // the worked example in 4.22: 28 links
    }

    @Test
    fun noLinksAtAllIsValid() {
        assertEquals(emptyList<FileError>(), Links.validate(file(links = ""), seed, drawing))
    }

    @Test
    fun aValueMayBeLinkedByMoreThanOneRegion() {
        val v = areaValueId()
        assertEquals(emptyList<FileError>(), Links.validate(file(links = entry(regionId(0), v) + "," + entry(regionId(1), v)), seed, drawing))
    }

    @Test
    fun everyCodeHasAFixture() {
        assertEquals(Codes.LINKS_ALL.toSet(), cases.map { it.code }.toSet())
    }

    @Test
    fun eachFixtureIsRejectedWithItsCodeAndPath() {
        for (c in cases) {
            val errors = Links.validate(c.text, seed, drawing)
            assertTrue("${c.name}: expected ${c.code} ${c.path}, got $errors", errors.any { it.code == c.code && it.path == c.path })
            assertTrue("${c.name}: must not be valid", Links.parse(c.text, seed, drawing) is Links.Invalid)
        }
    }

    @Test
    fun workedExamplesOfValidation() {
        // 4.22: a damage value on a region (the example's Dent), and a region the drawing does not have (the example's boot-lid).
        val damage = Links.validate(file(links = entry(regionId(0), valueIdOf(Role.DAMAGE))), seed, drawing)
        assertEquals(listOf("LINKS_VALUE_ROLE links[0].value"), damage.map { "${it.code} ${it.path}" })
        val unknown = Links.validate(file(links = entry("boot-lid", areaValueId())), seed, drawing)
        assertEquals(listOf("LINKS_REGION_UNKNOWN links[0].region"), unknown.map { "${it.code} ${it.path}" })
    }

    @Test
    fun theConfirmedLinksFileEntriesAreAllAreaValuesOfTheConfirmedSeed() {
        val roles = seed.lists.flatMap { l -> l.values.map { it.id to l.role } }.toMap()
        val entries = Json.parseToJsonElement(F.confirmedLinksText).jsonObject["links"]!!.jsonArray
        for (e in entries) assertEquals(Role.AREA, roles[e.jsonObject["value"]!!.jsonPrimitive.content])
    }
}
