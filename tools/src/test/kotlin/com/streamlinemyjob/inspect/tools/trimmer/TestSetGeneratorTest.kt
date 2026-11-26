package com.streamlinemyjob.inspect.tools.trimmer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The test set generator against designs/stage-B-trimmer.md 4.10 and 6 (`TestSetGeneratorTest`). */
class TestSetGeneratorTest {
    private val cases = TestSetGenerator.cases()
    private fun case(name: String) = cases.single { it.name == name }
    private val committed = File("../trimmer")

    @Test
    fun sameSeedGivesIdenticalFiles() {
        assertEquals(TestSetGenerator.files(), TestSetGenerator.files(TestSetGenerator.SEED))
    }

    @Test
    fun theCommittedFilesAreWhatTheSeedGives() {
        val files = TestSetGenerator.files(TestSetGenerator.SEED)
        assertEquals(17, files.size)
        for ((path, text) in files) {
            val f = File(committed, path)
            assertTrue("missing trimmer/$path", f.isFile)
            assertTrue("trimmer/$path differs from the generator (regenerate with tools trimmer-testset)", f.readBytes().contentEquals(text.toByteArray(Charsets.US_ASCII)))
        }
        val listed = File(committed, "testset").list().orEmpty().map { "testset/$it" }.toSet()
        assertEquals(files.keys.filter { it.startsWith("testset/") }.toSet(), listed)
    }

    @Test
    fun anotherSeedGivesOtherFiles() {
        val other = TestSetGenerator.files(TestSetGenerator.SEED + 1)
        assertNotEquals(TestSetGenerator.files()[TestSetGenerator.BAS_FILE], other[TestSetGenerator.BAS_FILE])
        assertNotEquals(TestSetGenerator.files()["testset/full.listing.txt"], other["testset/full.listing.txt"])
    }

    @Test
    fun everyCaseOf4101IsThereInOrderAndNamedInTheFile() {
        val names = listOf(
            "full", "reordered", "extra-columns", "few-columns", "blank-rows", "odd-disposal", "regs", "heading-case",
            "one-row", "taken-name", "text-numbers", "big", "missing", "twice", "empty", "canary",
        )
        assertEquals(names, cases.map { it.name })
        val bas = TestSetGenerator.files()[TestSetGenerator.BAS_FILE]!!
        assertTrue(bas.contains("CaseCount = 16"))
        names.forEachIndexed { i, n -> assertTrue("CaseName($i)", bas.contains("Case ${i + 1}: CaseName = \"$n\"\r\n")) }
    }

    /** 9: the generator reads nothing but its seed, so no real data can reach the test set. */
    @Test
    fun theGeneratorReadsNothingButItsSeed() {
        val dir = File("src/main/kotlin/com/streamlinemyjob/inspect/tools/trimmer")
        val banned = listOf("java.io", "java.nio", "File(", "readText", "readBytes", "readLines", "inputStream", "getResource",
            "System.getenv", "System.getProperty", "currentTimeMillis", "nanoTime", "kotlin.random", "Random(", "LocalDate", "Instant")
        for (name in listOf("TestSetGenerator.kt", "BasWriter.kt")) {
            val code = File(dir, name).readText()
            for (b in banned) assertFalse("$name uses $b", code.contains(b))
        }
    }

    @Test
    fun theFullCaseHasTheKeyloopHeadings60RowsAndEveryBlockValue() {
        val full = case("full").sheet
        assertEquals(TestSetGenerator.KEYLOOP_HEADINGS.map { Cell.Text(it) }, full.rows[0])
        assertEquals(25, full.rows[0].size)
        assertEquals(61, full.rows.size)
        val disposals = full.rows.drop(1).map { (it[21] as Cell.Text).text }.toSet()
        assertTrue(disposals.containsAll(listOf("RETAIL", "DEMO", "Courtesy", "Auction")))
        assertTrue(disposals.any { it != "Courtesy" && it.equals("Courtesy", ignoreCase = true) })
        assertTrue(case("full").expected.endsWith("ROWS 60\n"))
        assertFalse(case("full").expected.contains("\nOther |"))
    }

    @Test
    fun reorderedExtraColumnsAndBlankRowsGiveTheSameListingAsFull() {
        val full = case("full")
        for (n in listOf("reordered", "extra-columns", "blank-rows")) assertEquals(n, full.expected, case(n).expected)
        assertNotEquals(full.sheet.rows[0], case("reordered").sheet.rows[0])
        assertEquals(30, case("extra-columns").sheet.rows[0].size)
        val blank = case("blank-rows").sheet.rows.drop(1).filter { r -> r.all { it == Cell.Empty || (it is Cell.Text && it.text.isBlank()) } }
        assertEquals(6, blank.size)
        assertEquals(2, blank.count { r -> r.any { it is Cell.Text } })
        assertTrue(case("blank-rows").sheet.rows.last().all { it == Cell.Empty })
    }

    @Test
    fun theThreeMessageCasesGiveTheDesignsMessages() {
        assertEquals("The export is missing: Reg Number, Selling Price.", case("missing").expected)
        assertEquals("The export has \"Reg Number\" twice (columns H and K).", case("twice").expected)
        assertEquals("The export has no vehicles under its headings.", case("empty").expected)
        assertEquals(1, case("empty").sheet.rows.size)
    }

    @Test
    fun oneRowIsTheSecondWorkedExample() {
        val lines = case("one-row").expected.split('\n')
        val blank = " |  |  |  |  |  | "
        assertEquals(listOf("Retail |  |  |  |  |  | ", "none |  |  |  |  |  | ", blank, "Demo |  |  |  |  |  | ", "none |  |  |  |  |  | ", blank,
            "Courtesy |  |  |  |  |  | "), lines.subList(2, 9))
        assertTrue(lines[9].split(" | ")[1].isEmpty())
        assertEquals(listOf(blank, "Auction |  |  |  |  |  | ", "none |  |  |  |  |  | ", "ROWS 1", ""), lines.subList(10, 15))
    }

    @Test
    fun oddDisposalRegsHeadingCaseTakenNameAndTextNumbersHaveWhat4101Says() {
        val odd = case("odd-disposal")
        val other = odd.expected.substringAfter("Other |  |  |  |  |  | \n").lines().filter { it.contains("[ ]") }.map { it.split(" | ")[6] }
        assertEquals(listOf("Trade", "Retail 2", "", "Retail 2").sorted(), other.sorted())
        assertTrue(odd.expected.contains("| [ ] |  demo \n"))
        assertTrue(odd.expected.contains("| [ ] | AUCTION\n"))

        val regs = case("regs").sheet.rows.drop(1).map { it[1] }
        assertTrue(regs.contains(Cell.Number(1234.0)))
        assertTrue(regs.count { it == Cell.Empty } >= 2)
        val texts = regs.filterIsInstance<Cell.Text>().map { it.text }
        assertTrue(texts.any { it != it.uppercase() })
        assertTrue(texts.groupBy { TrimRules.regKey(Cell.Text(it)) }.values.any { g -> g.map { it }.toSet().size >= 3 })

        val hc = case("heading-case").sheet.rows[0].map { (it as Cell.Text).text }
        assertTrue(hc.contains(" reg number") && hc.contains("MILEAGE "))
        assertTrue(case("heading-case").expected.endsWith("ROWS 8\n"))
        assertEquals(listOf("Trimmed", "Trimmed (2)"), case("taken-name").oldTabs)

        val tn = case("text-numbers")
        for (r in tn.sheet.rows.drop(1)) {
            assertTrue(r[2] is Cell.Text && (r[2] as Cell.Text).numberFormat == "@")
            assertTrue(r[3] is Cell.Text && (r[3] as Cell.Text).numberFormat == "@")
            assertTrue(tn.expected.contains("| [ ] | ${(r[2] as Cell.Text).text} | ${(r[3] as Cell.Text).text} | [ ] |"))
        }
    }

    @Test
    fun theCanaryIsFewColumnsWithTwoRegsSwapped() {
        val few = case("few-columns")
        val canary = case("canary")
        assertEquals(few.sheet, canary.sheet)
        val a = few.expected.split('\n')
        val b = canary.expected.split('\n')
        assertEquals(a.size, b.size)
        val diff = a.indices.filter { a[it] != b[it] }
        assertEquals(2, diff.size)
        assertEquals(diff[0] + 1, diff[1])
        assertEquals(a[diff[0]].split(" | ")[1], b[diff[1]].split(" | ")[1])
        assertEquals(a[diff[1]].split(" | ")[1], b[diff[0]].split(" | ")[1])
    }

    @Test
    fun bigHas2000RowsAndItsExpectedCountsComeFromTheModel() {
        val big = case("big")
        assertEquals(2001, big.sheet.rows.size)
        val result = TrimRules.trim(big.sheet) as TrimOutcome.Result
        val lines = big.expected.trimEnd('\n').split('\n')
        assertEquals(result.blocks.map { "BLOCK ${it.name} ${it.rows.size}" } + "ROWS 2000", lines)
        assertEquals(listOf("Retail", "Demo", "Courtesy", "Auction", "Other"), result.blocks.map { it.name })
        // Each description is built from its own reg, mileage and price.
        for (r in big.sheet.rows.drop(1)) {
            val reg = (r[1] as? Cell.Text)?.text ?: ""
            val miles = (r[2] as Cell.Number).value.toInt()
            val pence = Math.round((r[3] as Cell.Number).value * 100).toInt()
            assertEquals(Cell.Text(TestSetGenerator.bigDescription(reg, miles, pence)), r[0])
        }
    }

    /** The minimal standard generator's published check value: from seed 1, the 10,000th value is 1043618065. */
    @Test
    fun minStdGivesThePublishedValues() {
        val g = MinStd(1, raw = true)
        assertEquals(16807, g.next())
        repeat(9998) { g.next() }
        assertEquals(1043618065, g.next())
    }

    @Test
    fun onlyMadeUpValuesInTheShapesOf4101() {
        val reg = Regex("""^\s*([A-Za-z]{2}[Zz] ?[0-9]{4}|[A-Za-z]{2} ?[0-9]{2} ?[A-Za-z]{3}|[A-Z]{3} [0-9]{1,3}|1234)\s*$""")
        val desc = Regex("""^Model [A-Z] [0-9]\.[0-9] \w+$""")
        for (c in cases.filter { it.name != "big" }) {
            val head = c.sheet.rows[0].map { (it as? Cell.Text)?.text?.trim()?.lowercase() }
            val regCol = head.indexOf("reg number").takeIf { it >= 0 } ?: head.indexOf("reg no")
            val descCol = head.indexOf("veh description")
            val mileCol = head.indexOf("mileage")
            val priceCol = head.indexOf("selling price")
            for (r in c.sheet.rows.drop(1)) {
                when (val cell = r.getOrElse(regCol) { Cell.Empty }) {
                    is Cell.Text -> if (cell.text.isNotBlank()) assertTrue("${c.name} reg '${cell.text}'", reg.matches(cell.text))
                    is Cell.Number -> assertEquals(1234.0, cell.value, 0.0)
                    Cell.Empty -> {}
                }
                val d = r.getOrElse(descCol) { Cell.Empty }
                if (d is Cell.Text && d.text.isNotBlank()) assertTrue("${c.name} description '${d.text}'", desc.matches(d.text))
                val miles = r.getOrElse(mileCol) { Cell.Empty }
                if (miles is Cell.Number) assertTrue("${c.name} mileage", miles.value in 0.0..120_000.0)
                val price = r.getOrElse(priceCol) { Cell.Empty }
                if (price is Cell.Number) assertTrue("${c.name} price", price.value == 0.0 || price.value in 5_000.0..45_000.0)
            }
        }
    }

    @Test
    fun theGeneratedFilePassesBasLint() {
        val bas = TestSetGenerator.files()[TestSetGenerator.BAS_FILE]!!
        assertEquals(emptyList<BasLint.Problem>(), BasLint.check(TestSetGenerator.BAS_FILE, bas.toByteArray(Charsets.US_ASCII)))
    }

    /** Reading the generated VBA back gives each case's own cells and its expected text (4.10.3). */
    @Test
    fun theGeneratedVbaFillsEachCaseCellByCellAndReturnsItsExpectedText() {
        val bas = TestSetGenerator.files()[TestSetGenerator.BAS_FILE]!!
        val procs = procedures(bas)
        cases.forEachIndexed { i, c ->
            val n = i + 1
            assertEquals(c.name, c.expected, readExpected(procs, "Expected_$n"))
            if (c.bigSeed != null) {
                assertTrue(bas.contains("Private Const BIG_SEED As Long = ${c.bigSeed}\r\n"))
                return@forEachIndexed
            }
            val cells = HashMap<Pair<Int, Int>, Cell>()
            val formats = HashMap<Pair<Int, Int>, String>()
            for (line in body(procs, "Case_$n")) {
                val m = Regex("""^ws\.Cells\((\d+), (\d+)\)\.(Value|NumberFormat) = (.*)$""").find(line) ?: continue
                val at = m.groupValues[1].toInt() - 1 to m.groupValues[2].toInt() - 1
                val v = m.groupValues[4]
                if (m.groupValues[3] == "NumberFormat") {
                    formats[at] = unquote(v)
                } else {
                    val f = formats[at] ?: Cell.GENERAL
                    cells[at] = if (v.startsWith("\"")) Cell.Text(unquote(v), f) else Cell.Number(v.toDouble(), f)
                }
            }
            val rows = c.sheet.rows.size
            val cols = c.sheet.rows.maxOf { it.size }
            val rebuilt = ExportSheet(List(rows) { r -> List(c.sheet.rows[r].size) { col -> cells[r to col] ?: Cell.Empty } })
            assertEquals(c.name, c.sheet, rebuilt)
            assertTrue(c.name, cells.keys.all { (r, col) -> r < rows && col < cols })
            for (t in c.oldTabs) assertTrue(body(procs, "Case_$n").contains("AddOldTab ws.Parent, \"$t\""))
        }
    }

    private fun procedures(bas: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var cur: MutableList<String>? = null
        for (raw in bas.split("\r\n")) {
            val start = Regex("""^Private (?:Sub|Function) (\w+)\(""").find(raw)
            when {
                start != null -> cur = mutableListOf<String>().also { out[start.groupValues[1]] = it }
                raw.startsWith("End Sub") || raw.startsWith("End Function") -> cur = null
                cur != null -> cur += raw.trim()
            }
        }
        return out
    }

    /** A procedure's lines, with calls to its `_part<k>` pieces followed. */
    private fun body(procs: Map<String, List<String>>, name: String): List<String> =
        procs.getValue(name).flatMap { line ->
            val call = Regex("""^(${name}_part\d+) ws$""").find(line)
            if (call != null) body(procs, call.groupValues[1]) else listOf(line)
        }

    private fun readExpected(procs: Map<String, List<String>>, name: String): String {
        val lines = procs.getValue(name)
        val joined = Regex("""^$name = (.*)$""").find(lines.first { it.startsWith("$name = ") })!!.groupValues[1]
        if (joined != "s") return joined.split(" & ").joinToString("") { readExpected(procs, it.removeSuffix("()")) }
        return buildString {
            for (l in lines) {
                val m = Regex("""^s = s & ("(?:[^"]|"")*")( & vbLf)?$""").find(l) ?: continue
                append(unquote(m.groupValues[1]))
                if (m.groupValues[2].isNotEmpty()) append('\n')
            }
        }
    }

    private fun unquote(lit: String): String {
        require(lit.startsWith("\"") && lit.endsWith("\"")) { lit }
        return lit.substring(1, lit.length - 1).replace("\"\"", "\"")
    }
}
