package com.streamlinemyjob.inspect.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.streamlinemyjob.inspect.core.DrawingFixtures as F

/** The seed phase's validator over the three files (designs/stage-1-foundation.md 4.5, C19): the seed, then the drawing, then the link file against both. */
class ValidateAllTest {
    private fun run(seed: String = F.confirmedSeedText, drawing: String = F.confirmedDrawingText, links: String = F.confirmedLinksText): List<String> {
        val dir = kotlin.io.path.createTempDirectory("validate-all").toFile()
        try {
            val s = File(dir, "lists.json").apply { writeText(seed) }
            val d = File(dir, "car-top.json").apply { writeText(drawing) }
            val l = File(dir, "links.json").apply { writeText(links) }
            return validateAll(s, d, l)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theConfirmedFilesAreAllValid() {
        assertEquals(emptyList<String>(), run())
    }

    @Test
    fun aBrokenLinkFileIsReportedWithItsCodeAndPath() {
        val broken = F.confirmedLinksText.replaceFirst(Regex("\"region\"\\s*:\\s*\"[^\"]*\""), "\"region\": \"no-such-region\"")
        val lines = run(links = broken)
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].startsWith("LINKS_REGION_UNKNOWN links[0].region"))
    }

    @Test
    fun aBrokenDrawingIsReportedAndTheLinkFileIsNotJudgedAgainstIt() {
        val lines = run(drawing = F.confirmedDrawingText.replace("\"smj-drawing\"", "\"nope\""))
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].startsWith("DRAWING_FORMAT format"))
    }

    @Test
    fun aBrokenSeedIsReportedAndTheOthersStillRun() {
        val lines = run(seed = "{broken", drawing = "{also broken")
        assertEquals(listOf("SEED_JSON", "DRAWING_JSON"), lines.map { it.substringBefore(' ') })
    }

    @Test
    fun aMissingFileIsAnErrorNotACrash() {
        val dir = kotlin.io.path.createTempDirectory("validate-all-missing").toFile()
        try {
            val lines = validateAll(File(dir, "a"), File(dir, "b"), File(dir, "c"))
            assertEquals(listOf("SEED_JSON", "DRAWING_JSON", "LINKS_JSON"), lines.map { it.substringBefore(' ') })
        } finally {
            dir.deleteRecursively()
        }
    }
}
