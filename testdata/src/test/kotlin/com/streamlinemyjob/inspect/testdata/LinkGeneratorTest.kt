package com.streamlinemyjob.inspect.testdata

import com.streamlinemyjob.inspect.core.Drawing
import com.streamlinemyjob.inspect.core.Hit
import com.streamlinemyjob.inspect.core.Codes
import com.streamlinemyjob.inspect.core.LinkData
import com.streamlinemyjob.inspect.core.Links
import com.streamlinemyjob.inspect.core.Pt
import com.streamlinemyjob.inspect.core.Region
import com.streamlinemyjob.inspect.core.Seed
import com.streamlinemyjob.inspect.core.SeedData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `LinkGenerator` and `PointSampler` (designs/stage-1-foundation.md 4.26, S1.26's done-when): the
 * confirmed seed and link file, and 20 generated seeds each with 3 generated link files. Each link file
 * validates; 50 sampled points per region resolve to that region, whose link in the file is the expected
 * value or none; the generator's extremes are each hit.
 */
class LinkGeneratorTest {
    private fun random(test: String) = TestRandom.forTest("${LinkGeneratorTest::class.qualifiedName}.$test")

    private fun fileText(property: String) = File(System.getProperty(property) ?: error("$property not set")).readText()

    private val drawing: Drawing by lazy { (Drawing.parse(fileText("smj.drawingFile")) as Drawing.Companion.Valid).drawing }
    private val confirmedSeedText: String by lazy { fileText("smj.seedFile") }
    private val confirmedSeed: SeedData by lazy { (Seed.parse(confirmedSeedText) as Seed.Valid).seed }
    private val confirmedLinksText: String by lazy { fileText("smj.linksFile") }

    private fun parse(linksText: String, seed: SeedData): LinkData {
        val p = Links.parse(linksText, seed, drawing)
        assertTrue("$p", p is Links.Valid)
        return (p as Links.Valid).links
    }

    /** What the file itself says each region links to, read with the JSON reader and not with `Links`. */
    private fun expectedLinks(linksText: String): Map<String, String> =
        Json.parseToJsonElement(linksText).jsonObject["links"]!!.jsonArray.associate {
            it.jsonObject["region"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonPrimitive.content
        }

    /** 50 points per region resolve to it, and the region's link (from the parsed file) is what the file's text says, or none. */
    private fun checkSamples(r: java.util.Random, seed: SeedData, linksText: String) {
        val links = parse(linksText, seed)
        val byRegion = links.links.associate { it.region to it.value }
        val expected = expectedLinks(linksText)
        assertEquals(expected, byRegion)
        for (region in drawing.regions) {
            repeat(50) {
                val p = PointSampler.inside(r, drawing, region.id)
                assertEquals("${region.id} at (${p.x}, ${p.y})", region.id, Hit.resolve(drawing, p.x, p.y))
                assertEquals(expected[region.id], byRegion[region.id])
            }
        }
    }

    @Test
    fun theConfirmedSeedAndLinkFile() {
        assertEquals(emptyList<Any>(), Links.validate(confirmedLinksText, confirmedSeed, drawing))
        checkSamples(random("confirmed"), confirmedSeed, confirmedLinksText)
    }

    @Test
    fun twentyGeneratedSeedsEachWithThreeGeneratedLinkFiles() {
        val r = random("twenty")
        val avoid = confirmedSeed.lists.map { it.name }
        repeat(20) { n ->
            val seedText = SeedGenerator.generate(r, avoid)
            val seed = (Seed.parse(seedText) as Seed.Valid).seed
            repeat(3) { m ->
                // The first seed's three files are the three forced extremes; every other file is drawn.
                val force = if (n == 0) LinkGenerator.Extreme.entries[m] else null
                val text = LinkGenerator.generate(r, seedText, drawing, force)
                assertEquals("seed $n file $m", emptyList<Any>(), Links.validate(text, seed, drawing))
                checkSamples(r, seed, text)
            }
        }
    }

    @Test
    fun theGeneratorsExtremesAreEachHit() {
        val r = random("extremes")
        repeat(10) {
            val seedText = SeedGenerator.generate(r, confirmedSeed.lists.map { it.name })
            val seed = (Seed.parse(seedText) as Seed.Valid).seed
            val none = parse(LinkGenerator.generate(r, seedText, drawing, LinkGenerator.Extreme.NONE_LINKED), seed)
            assertEquals(0, none.links.size)
            val all = parse(LinkGenerator.generate(r, seedText, drawing, LinkGenerator.Extreme.ALL_LINKED), seed)
            assertEquals(drawing.regions.map { it.id }.toSet(), all.links.map { it.region }.toSet())
            val two = parse(LinkGenerator.generate(r, seedText, drawing, LinkGenerator.Extreme.TWO_ON_ONE_VALUE), seed)
            assertTrue("two regions share a value", two.links.groupingBy { it.value }.eachCount().values.any { it >= 2 })
        }
    }

    @Test
    fun withoutAnExtremeSomeRegionsAreLinkedAndSomeAreNot() {
        val r = random("mixed")
        var sawUnlinked = false
        var sawLinked = false
        repeat(40) {
            val seedText = SeedGenerator.generate(r, confirmedSeed.lists.map { it.name })
            val n = parse(LinkGenerator.generate(r, seedText, drawing), (Seed.parse(seedText) as Seed.Valid).seed).links.size
            sawUnlinked = sawUnlinked || n < drawing.regions.size
            sawLinked = sawLinked || n > 0
        }
        assertTrue(sawUnlinked && sawLinked)
    }

    @Test
    fun aGeneratedSeedWithTheConfirmedLinkFileIsRejectedForEveryEntry() {
        val r = random("belongs-to-its-seed")
        val seedText = SeedGenerator.generate(r, confirmedSeed.lists.map { it.name })
        val seed = (Seed.parse(seedText) as Seed.Valid).seed
        val errors = Links.validate(confirmedLinksText, seed, drawing)
        val entries = expectedLinks(confirmedLinksText).size
        assertEquals(List(entries) { Codes.LINKS_VALUE_UNKNOWN }, errors.map { it.code })
        assertEquals(List(entries) { "links[$it].value" }, errors.map { it.path })
    }

    @Test
    fun aRegionNoPointCanBeDrawnInThrowsAfterTheLimit() {
        // A flat region (all its points on one line) has no inside, so no uniformly drawn point lands in it: the sampler gives up, it does not loop.
        val flat = Drawing(
            "t", 1000, 1000,
            listOf(Region("flat", Pt(500, 500), listOf(Pt(0, 0), Pt(1000, 1000), Pt(500, 500)))),
            emptyList(), emptyList(),
        )
        try {
            PointSampler.inside(random("flat"), flat, "flat")
            throw AssertionError("expected the sampler to throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("too small"))
        }
    }
}
