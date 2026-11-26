package com.streamlinemyjob.inspect.tools.trimmer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reference model against designs/stage-B-trimmer.md 4.3 to 4.8 and 4.10.2. */
class TrimRulesTest {
    private val price = "#,##0.00"
    private fun t(s: String) = Cell.Text(s)
    private fun n(v: Double, f: String = Cell.GENERAL) = Cell.Number(v, f)
    private val e = Cell.Empty

    /** An export sheet from a heading row and rows of cells (strings are text, numbers General, null empty). */
    private fun sheet(headings: List<String>, vararg rows: List<Any?>) = ExportSheet(
        listOf(headings.map { if (it.isEmpty()) e else t(it) }) + rows.map { r ->
            r.map {
                when (it) {
                    null -> e
                    is Cell -> it
                    is String -> t(it)
                    is Number -> n(it.toDouble())
                    else -> error("cell $it")
                }
            }
        },
    )

    private val five = TrimRules.KEPT

    private fun result(s: ExportSheet) = TrimRules.trim(s) as TrimOutcome.Result
    private fun message(s: ExportSheet) = (TrimRules.trim(s) as TrimOutcome.Message).text
    private fun regsIn(s: ExportSheet, block: String) =
        result(s).blocks.single { it.name == block }.rows.map { TrimRules.listed(it.reg) }

    private fun empty(k: Int = 7) = List(k) { "" }.joinToString(" | ")
    private fun title(name: String) = (listOf(name) + List(6) { "" }).joinToString(" | ")
    private val headingLine = "Veh Description | Reg Number |  | Mileage | Selling Price |  | Disposal Method"

    // The 4.5 worked example, with two columns that are not kept around the kept ones.
    private val example1 = sheet(
        listOf("Stock No", "Veh Description", "Reg Number", "Colour", "Mileage", "Selling Price", "Disposal Method"),
        listOf("S1", "Model A 1.5 5dr", "ABZ 1234", "Red", 5301, n(18995.0, price), "RETAIL"),
        listOf("S2", "Model B 1.8 Auto", "HT75ABC", "Blue", 4950, n(0.0, price), "DEMO"),
        listOf("S3", "Model C 1.0 5dr", "abz1200", "Red", 2696, n(0.0, price), "Retail"),
        listOf("S4", "Model E 1.3 5dr", null, "Grey", 8205, n(0.0, price), "RETAIL"),
        listOf("S5", "Model D 2.0 Van", "CDZ5678", "White", 132, n(26994.0, price), "Trade"),
        listOf("S6", "Model F 1.2 3dr", "ABZ1234", "Black", 12, n(31554.0, price), "RETAIL"),
    )

    @Test
    fun firstWorkedExampleGivesTheListingOf46() {
        val expected = listOf(
            headingLine,
            empty(),
            title("Retail"),
            "Model C 1.0 5dr | abz1200 | [ ] | 2696 | 0 {#,##0.00} | [ ] | Retail",
            "Model A 1.5 5dr | ABZ 1234 | [ ] | 5301 | 18995 {#,##0.00} | [ ] | RETAIL",
            "Model F 1.2 3dr | ABZ1234 | [ ] | 12 | 31554 {#,##0.00} | [ ] | RETAIL",
            "Model E 1.3 5dr |  | [ ] | 8205 | 0 {#,##0.00} | [ ] | RETAIL",
            empty(),
            title("Demo"),
            "Model B 1.8 Auto | HT75ABC | [ ] | 4950 | 0 {#,##0.00} | [ ] | DEMO",
            empty(),
            title("Courtesy"),
            title("none"),
            empty(),
            title("Auction"),
            title("none"),
            empty(),
            title("Other"),
            "Model D 2.0 Van | CDZ5678 | [ ] | 132 | 26994 {#,##0.00} | [ ] | Trade",
            "ROWS 6",
        ).joinToString("\n", postfix = "\n")
        assertEquals(expected, result(example1).listing())
    }

    @Test
    fun secondWorkedExampleOneCourtesyRowNoRegHasNoOtherBlockAndNoTrailingEmptyRow() {
        val s = sheet(five, listOf("Model G 1.0 5dr", null, 100, n(0.0, price), "Courtesy"))
        val expected = listOf(
            headingLine, empty(),
            title("Retail"), title("none"), empty(),
            title("Demo"), title("none"), empty(),
            title("Courtesy"), "Model G 1.0 5dr |  | [ ] | 100 | 0 {#,##0.00} | [ ] | Courtesy", empty(),
            title("Auction"), title("none"),
            "ROWS 1",
        ).joinToString("\n", postfix = "\n")
        assertEquals(expected, result(s).listing())
    }

    @Test
    fun thirdWorkedExampleRegNumberTwiceInHAndKStops() {
        val h = listOf("A1", "Veh Description", "C1", "Mileage", "E1", "Selling Price", "Disposal Method", "Reg Number", "I1", "J1", "Reg Number")
        val s = sheet(h, List(11) { "x" })
        assertEquals("The export has \"Reg Number\" twice (columns H and K).", message(s))
    }

    @Test
    fun missingHeadingsAreListedInKeptColumnOrderAndRegNoIsNotRegNumber() {
        val s = sheet(listOf("Disposal Method", "Reg No", "Mileage", "Veh Description"), listOf("RETAIL", "AB1", 1, "x"))
        assertEquals("The export is missing: Reg Number, Selling Price.", message(s))
        val s2 = sheet(listOf("Veh Description", "Reg Number 2", "Mileage", "Selling Price", "Disposal Method"), listOf("x", "AB1", 1, 2, "DEMO"))
        assertEquals("The export is missing: Reg Number.", message(s2))
    }

    @Test
    fun everyProblemIsListedAtOnce() {
        val s = sheet(listOf("Veh Description", "Mileage", "Mileage", "Selling Price", "Disposal Method", "Disposal Method", "Disposal Method"), List(7) { "x" })
        assertEquals(
            "The export is missing: Reg Number. The export has \"Mileage\" twice (columns B and C). " +
                "The export has \"Disposal Method\" 3 times (columns E, F and G).",
            message(s),
        )
    }

    @Test
    fun headingsOnlyOrOnlyBlankRowsIsTheNoVehiclesMessage() {
        assertEquals("The export has no vehicles under its headings.", message(sheet(five)))
        assertEquals("The export has no vehicles under its headings.", message(sheet(five, List(5) { null }, listOf("  ", null, " ", null, null))))
    }

    @Test
    fun headingsMatchTrimmedAndCaseInsensitivelyInAnyOrder() {
        val s = sheet(
            listOf(" disposal METHOD", "Junk", " reg number ", "MILEAGE ", "selling price", "veh description"),
            listOf("RETAIL", "j", "ZZ1", 1, 2, "Car 1"),
        )
        assertEquals("Car 1 | ZZ1 | [ ] | 1 | 2 | [ ] | RETAIL", result(s).listing().lines()[3])
        assertTrue(TrimRules.headingMatches(t(" reg number "), "Reg Number"))
        assertTrue(!TrimRules.headingMatches(t("Reg No"), "Reg Number"))
        assertTrue(!TrimRules.headingMatches(t("Reg Number 2"), "Reg Number"))
    }

    @Test
    fun blankRowsAreSkippedAndNotCountedEvenWhenOnlySpaces() {
        val s = sheet(
            five,
            listOf("Car 1", "AB1", 1, 2, "DEMO"),
            List(5) { null },
            listOf("   ", null, " ", null, null),
            listOf("Car 2", "AB2", 3, 4, "DEMO"),
        )
        val r = result(s)
        assertEquals(2, r.vehicleCount)
        assertTrue(r.listing().endsWith("ROWS 2\n"))
    }

    @Test
    fun aRowWithOnlyANonKeptCellIsAVehicleRowAndGoesToOther() {
        val s = sheet(five + "Notes", listOf(null, null, null, null, null, "note"))
        val r = result(s)
        assertEquals(1, r.vehicleCount)
        assertEquals(listOf(""), regsIn(s, "Other"))
    }

    @Test
    fun courtesyAndEveryBlockMatchIgnoringCaseAndEndSpaces() {
        assertEquals("Courtesy", TrimRules.blockOf(t("courtesy")))
        assertEquals("Courtesy", TrimRules.blockOf(t("COURTESY")))
        assertEquals("Retail", TrimRules.blockOf(t(" retail ")))
        assertEquals("Demo", TrimRules.blockOf(t(" demo ")))
        assertEquals("Auction", TrimRules.blockOf(t("AUCTION")))
    }

    @Test
    fun anythingElseGoesToOtherNeverByFirstLetterOrContains() {
        for (v in listOf("Trade", "Retail 2", "R", "", "Demonstrator", "ex-Courtesy")) {
            assertEquals(v, "Other", TrimRules.blockOf(t(v)))
        }
        assertEquals("Other", TrimRules.blockOf(e))
        assertEquals("Other", TrimRules.blockOf(n(1.0)))
    }

    @Test
    fun theDisposalTextWrittenIsTheExportsOwnNotTheBlockName() {
        val s = sheet(five, listOf("Car 1", "AB1", 1, 2, " courtesy "))
        assertTrue(result(s).listing().contains("Car 1 | AB1 | [ ] | 1 | 2 | [ ] |  courtesy \n"))
    }

    @Test
    fun emptyBlocksAreShownWithNoneAndOtherOnlyWhenItHasCars() {
        val s = sheet(five, listOf("Car 1", "AB1", 1, 2, "RETAIL"))
        val r = result(s)
        assertEquals(listOf("Retail", "Demo", "Courtesy", "Auction"), r.blocks.map { it.name })
        val l = r.listing().lines()
        listOf("Demo", "Courtesy", "Auction").forEach { assertEquals(title("none"), l[l.indexOf(title(it)) + 1]) }
        assertTrue(!r.listing().contains("Other"))
    }

    @Test
    fun regKeyIgnoresCaseSpacesTabsAndNonBreakingSpaces() {
        assertEquals("ABZ1234", TrimRules.regKey(t("abz 1234")))
        assertEquals("ABZ1234", TrimRules.regKey(t(" A B\tZ 1234 ")))
        assertEquals("1234", TrimRules.regKey(n(1234.0)))
        assertEquals("", TrimRules.regKey(t("   ")))
        assertEquals("", TrimRules.regKey(e))
    }

    @Test
    fun sortIgnoresSpacesInTheKeyButShowsTheRegAsTyped() {
        val s = sheet(
            five,
            listOf("Car 1", "AB 13", 1, 2, "DEMO"),
            listOf("Car 2", "AB12", 1, 2, "DEMO"),
            listOf("Car 3", "A B 11", 1, 2, "DEMO"),
            listOf("Car 4", "AB1 0", 1, 2, "DEMO"),
        )
        assertEquals(listOf("AB1 0", "A B 11", "AB12", "AB 13"), regsIn(s, "Demo"))
    }

    @Test
    fun tiesKeepExportOrderSameKeyDifferentSpacing() {
        val s = sheet(
            five,
            listOf("Car 1", "zz 99", 1, 2, "RETAIL"),
            listOf("Car 2", "ZZ99", 1, 2, "RETAIL"),
            listOf("Car 3", "AA 1", 1, 2, "RETAIL"),
            listOf("Car 4", "Z Z 9 9", 1, 2, "RETAIL"),
            listOf("Car 5", "zZ99 ", 1, 2, "RETAIL"),
        )
        val descriptions = result(s).blocks[0].rows.map { TrimRules.listed(it.description) }
        assertEquals(listOf("Car 3", "Car 1", "Car 2", "Car 4", "Car 5"), descriptions)
    }

    @Test
    fun rowsWithNoRegGoLastInExportOrder() {
        val s = sheet(
            five,
            listOf("Car 1", null, 1, 2, "AUCTION"),
            listOf("Car 2", "BB1", 1, 2, "AUCTION"),
            listOf("Car 3", "  ", 1, 2, "AUCTION"),
            listOf("Car 4", "AA1", 1, 2, "AUCTION"),
            listOf("Car 5", null, 1, 2, "AUCTION"),
        )
        val descriptions = result(s).blocks.single { it.name == "Auction" }.rows.map { TrimRules.listed(it.description) }
        assertEquals(listOf("Car 4", "Car 2", "Car 1", "Car 3", "Car 5"), descriptions)
    }

    @Test
    fun keysCompareByCharacterCodeDigitsBeforeLettersHyphenFirst() {
        val s = sheet(
            five,
            listOf("Car 1", "b2", 1, 2, "DEMO"),
            listOf("Car 2", n(1234.0), 1, 2, "DEMO"),
            listOf("Car 3", "A-1", 1, 2, "DEMO"),
            listOf("Car 4", "A1", 1, 2, "DEMO"),
            listOf("Car 5", "9Z", 1, 2, "DEMO"),
        )
        assertEquals(listOf("1234", "9Z", "A-1", "A1", "b2"), regsIn(s, "Demo"))
    }

    @Test
    fun numbersAreListedInTheShortestFormWithTheirFormatTextAsIs() {
        assertEquals("5301", TrimRules.listed(n(5301.0)))
        assertEquals("0", TrimRules.listed(n(0.0)))
        assertEquals("13495.01", TrimRules.listed(n(13495.01)))
        assertEquals("30354 {#,##0.00}", TrimRules.listed(n(30354.0, price)))
        assertEquals("120000", TrimRules.listed(n(120000.0)))
        assertEquals("1234567890123", TrimRules.listed(n(1234567890123.0)))
        assertEquals("0.0001", TrimRules.listed(n(0.0001)))
        assertEquals("-5.5", TrimRules.listed(n(-5.5)))
        assertEquals("30,354.00", TrimRules.listed(Cell.Text("30,354.00", "@")))
        assertEquals(" 5301 ", TrimRules.listed(t(" 5301 ")))
        assertEquals("", TrimRules.listed(e))
    }

    @Test
    fun textNumbersStayTextAndValuesAreNotChanged() {
        val s = sheet(five, listOf(" Car 1 ", "ab 1", Cell.Text("5301", "@"), Cell.Text("18,995.00", "@"), "RETAIL"))
        assertTrue(result(s).listing().contains(" Car 1  | ab 1 | [ ] | 5301 | 18,995.00 | [ ] | RETAIL\n"))
    }

    @Test
    fun rowCountReadEqualsRowsWritten() {
        val rows = (1..37).map { i -> listOf("Car $i", if (i % 5 == 0) null else "R${(i * 7) % 11}", i, 2, listOf("RETAIL", "DEMO", "Courtesy", "AUCTION", "x", "")[i % 6]) }
        val r = result(sheet(five, *rows.toTypedArray()))
        assertEquals(37, r.vehicleCount)
        assertEquals(37, r.blocks.sumOf { it.rows.size })
        val vehicleLines = r.listing().lines().count { it.contains(" | [ ] | ") }
        assertEquals(37, vehicleLines)
    }

    @Test
    fun columnLetters() {
        assertEquals(listOf("A", "H", "K", "Z", "AA", "AZ", "BA", "ZZ", "AAA"), listOf(0, 7, 10, 25, 26, 51, 52, 701, 702).map(TrimRules::columnLetter))
    }
}
