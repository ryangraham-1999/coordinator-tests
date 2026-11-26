package com.streamlinemyjob.inspect.tools.trimmer

/**
 * Builds the trimmer's test set (designs/stage-B-trimmer.md 4.10): the cases of 4.10.1, each an export
 * sheet with its expected listing or message worked out by the reference model ([TrimRules]), and the
 * text of the two generated outputs, `trimmer/TrimmerTestData.bas` (4.10.3) and `trimmer/testset/`.
 *
 * It reads nothing but its seed: no files, no environment, no clock (9). Every value is made up from
 * [MinStd], the same small generator the `big` case runs inside VBA, so the output is the same on every
 * run and on every machine. Writing the files is left to the caller (`tools trimmer-testset`).
 */
object TestSetGenerator {
    const val SEED = 20261124L

    /** The cases of 4.10.1, in the design's order; their number in the generated file is their place here, from 1. */
    val CASE_NAMES = listOf(
        "full", "reordered", "extra-columns", "few-columns", "blank-rows", "odd-disposal", "regs", "heading-case",
        "one-row", "taken-name", "text-numbers", "big", "missing", "twice", "empty", "canary",
    )

    /** The 25 Keyloop headings, columns A to Y, in the photo order (PLAN.md stage B). */
    val KEYLOOP_HEADINGS = listOf(
        "StkNo", "VSeq", "Branch", "Buyer", "Stat", "Veh Description", "Chassis", "Reg Number", "Reg Date",
        "Colour Desc", "Mileage", "Veh Cost", "Total NR Costs", "Standing Price", "Days", "No", "Purch Date",
        "VType", "Selling Price", "Veh Location", "Qualify for VAT?", "Disposal Method", "Next Service date",
        "MOT Expiry date", "SR Status",
    )

    const val PRICE_FORMAT = "#,##0.00"
    const val DATE_FORMAT = "dd/mm/yyyy"
    const val TEXT_FORMAT = "@"
    const val BIG_ROWS = 2000
    const val BAS_FILE = "TrimmerTestData.bas"
    const val TESTSET_DIR = "testset"

    /** Old tabs a case's workbook already has before the trim (case `taken-name`). */
    data class TestCase(
        val name: String,
        val sheet: ExportSheet,
        val expected: String,
        val oldTabs: List<String> = emptyList(),
        /** Set only for `big`: the seed of the generator that fills it inside VBA. */
        val bigSeed: Int? = null,
    )

    fun cases(seed: Long = SEED): List<TestCase> {
        val rnd = MinStd(seed)
        val fullRows = List(60) { vehicleRow(rnd, KEYLOOP_HEADINGS, fullDisposal(it, rnd)) }
        val full = ExportSheet(listOf(headings(KEYLOOP_HEADINGS)) + fullRows)

        val order = shuffled(KEYLOOP_HEADINGS.indices.toList(), rnd).let { if (it == KEYLOOP_HEADINGS.indices.toList()) it.reversed() else it }
        val reordered = ExportSheet(full.rows.map { row -> order.map { row.getOrElse(it) { Cell.Empty } } })

        val extra = extraColumns(full, rnd)
        val blank = blankRows(full, rnd)

        val few = fewColumns(rnd)
        val big = bigCase(seed)

        val list = listOf(
            case("full", full),
            case("reordered", reordered),
            case("extra-columns", extra),
            case("few-columns", few),
            case("blank-rows", blank),
            case("odd-disposal", oddDisposal(rnd)),
            case("regs", regs(rnd)),
            case("heading-case", headingCase(rnd)),
            case("one-row", oneRow(rnd)),
            case("taken-name", takenName(rnd)).copy(oldTabs = listOf("Trimmed", "Trimmed (2)")),
            case("text-numbers", textNumbers(rnd)),
            big,
            case("missing", missing(rnd)),
            case("twice", twice(rnd)),
            case("empty", ExportSheet(listOf(headings(KEYLOOP_HEADINGS)))),
            canary(few),
        )
        check(list.map { it.name } == CASE_NAMES)
        return list
    }

    /** The generated files, by path inside `trimmer/`: `TrimmerTestData.bas` and one `testset/<case>.listing.txt` per case. */
    fun files(seed: Long = SEED): Map<String, String> {
        val cases = cases(seed)
        val out = LinkedHashMap<String, String>()
        out[BAS_FILE] = BasWriter.write(cases, seed)
        for (c in cases) out["$TESTSET_DIR/${c.name}.listing.txt"] = c.expected
        return out
    }

    // ---- the cases -------------------------------------------------------------------------------

    private fun case(name: String, sheet: ExportSheet) = TestCase(name, sheet, expectedOf(sheet))

    /** The listing, or the message the trim stops with (4.10.1 "expected"). */
    fun expectedOf(sheet: ExportSheet): String = when (val o = TrimRules.trim(sheet)) {
        is TrimOutcome.Result -> o.listing()
        is TrimOutcome.Message -> o.text
    }

    private fun headings(names: List<String>): List<Cell> = names.map { Cell.Text(it) }

    /** `full`'s disposal values: all four block values, `Courtesy` also in upper and lower case. */
    private fun fullDisposal(i: Int, rnd: MinStd): String {
        val fixed = listOf("RETAIL", "DEMO", "Courtesy", "Auction", "COURTESY", "courtesy")
        return if (i < fixed.size) fixed[i] else rnd.pick(listOf("RETAIL", "RETAIL", "RETAIL", "DEMO", "DEMO", "Courtesy", "Auction"))
    }

    /** One made-up vehicle row under [names]; columns this model knows nothing about are left empty. */
    private fun vehicleRow(rnd: MinStd, names: List<String>, disposal: String, reg: Cell? = null): List<Cell> {
        val regCell = reg ?: if (rnd.below(15) == 0) Cell.Empty else Cell.Text(MadeUp.reg(rnd))
        return names.map { h ->
            when (h) {
                "StkNo" -> Cell.Number((90000 + rnd.below(9000)).toDouble())
                "VSeq" -> Cell.Number((1 + rnd.below(3)).toDouble())
                "Branch" -> Cell.Text("Branch ${1 + rnd.below(3)}")
                "Buyer" -> Cell.Text("Buyer ${1 + rnd.below(4)}")
                "Stat" -> Cell.Text(rnd.pick(listOf("IN", "OUT", "RES")))
                "Veh Description" -> Cell.Text(MadeUp.description(rnd))
                "Chassis" -> Cell.Text("TESTCHASSIS" + MadeUp.digits(rnd, 6))
                "Reg Number" -> regCell
                "Reg Date", "Purch Date", "Next Service date", "MOT Expiry date" ->
                    if (rnd.below(6) == 0) Cell.Empty else Cell.Number((44000 + rnd.below(2500)).toDouble(), DATE_FORMAT)
                "Colour Desc" -> Cell.Text(rnd.pick(listOf("Red", "Blue", "Grey", "White", "Black", "Silver")))
                "Mileage" -> Cell.Number(rnd.below(120_001).toDouble())
                "Veh Cost", "Total NR Costs", "Standing Price" -> Cell.Number(MadeUp.pennies(rnd) / 100.0, PRICE_FORMAT)
                "Days" -> Cell.Number(rnd.below(400).toDouble())
                "No" -> Cell.Number((1 + rnd.below(99)).toDouble())
                "VType" -> Cell.Text("U")
                "Selling Price" -> Cell.Number(MadeUp.price(rnd) / 100.0, PRICE_FORMAT)
                "Veh Location" -> Cell.Text("Site ${1 + rnd.below(4)}")
                "Qualify for VAT?" -> Cell.Text(rnd.pick(listOf("Y", "N")))
                "Disposal Method" -> Cell.Text(disposal)
                "SR Status" -> if (rnd.below(2) == 0) Cell.Empty else Cell.Text("Open")
                else -> Cell.Empty
            }
        }
    }

    /** `extra-columns`: five made-up columns inserted between the kept ones; their headings only look like kept ones. */
    private fun extraColumns(full: ExportSheet, rnd: MinStd): ExportSheet {
        val extras = listOf("Reg Number 2", "Mileage Band", "Price Was", "Reg No", "Disposal")
        val keptAt = TrimRules.KEPT.map { KEYLOOP_HEADINGS.indexOf(it) }
        // After each of the first four kept columns, and the fifth just before the last kept one.
        val insertAfter = listOf(keptAt[0], keptAt[1], keptAt[2], keptAt[3], keptAt[4] - 1)
        val rows = full.rows.mapIndexed { r, row ->
            val out = mutableListOf<Cell>()
            row.forEachIndexed { c, cell ->
                out += cell
                insertAfter.forEachIndexed { k, at ->
                    if (at == c) out += if (r == 0) Cell.Text(extras[k]) else Cell.Text("X${rnd.below(1000)}")
                }
            }
            out
        }
        return ExportSheet(rows)
    }

    /** `blank-rows`: `full` with 6 blank rows scattered, two of them holding only spaces. */
    private fun blankRows(full: ExportSheet, rnd: MinStd): ExportSheet {
        val rows = full.rows.drop(1).toMutableList()
        val width = KEYLOOP_HEADINGS.size
        val spaces = { List(width) { c -> if (c % 7 == 5) Cell.Text(" ".repeat(1 + c % 3)) else Cell.Empty } }
        val blanks = listOf(spaces(), spaces()) + List(4) { List(width) { Cell.Empty } }
        for ((k, b) in blanks.withIndex()) {
            // The last one goes below the last vehicle row, so a blank row at the end is covered too.
            val at = if (k == blanks.lastIndex) rows.size else rnd.below(rows.size + 1)
            rows.add(at, b)
        }
        return ExportSheet(listOf(full.rows[0]) + rows)
    }

    /** `few-columns`: only the five kept columns, in kept order. */
    private fun fewColumns(rnd: MinStd): ExportSheet {
        val disposals = listOf("RETAIL", "DEMO", "Courtesy", "Auction", "RETAIL", "DEMO", "Trade")
        val rows = List(24) { i -> vehicleRow(rnd, TrimRules.KEPT, if (i < disposals.size) disposals[i] else rnd.pick(disposals.take(4))) }
        return ExportSheet(listOf(headings(TrimRules.KEPT)) + rows)
    }

    /** `odd-disposal`: `Trade`, `Retail 2`, empty, ` demo `, `AUCTION`, among ordinary ones. */
    private fun oddDisposal(rnd: MinStd): ExportSheet {
        val values = listOf("Trade", "RETAIL", "Retail 2", "", " demo ", "Courtesy", "AUCTION", "Retail 2", "DEMO")
        val rows = values.map { d ->
            vehicleRow(rnd, TrimRules.KEPT, d, reg = Cell.Text(MadeUp.reg(rnd))).let { row ->
                if (d.isEmpty()) row.mapIndexed { c, cell -> if (c == 4) Cell.Empty else cell } else row
            }
        }
        return ExportSheet(listOf(headings(TrimRules.KEPT)) + rows)
    }

    /** `regs`: spaces, lower case, duplicates (same key, different spacing), empty regs, a number-typed reg. */
    private fun regs(rnd: MinStd): ExportSheet {
        val a = MadeUp.gbReg(rnd)                          // e.g. KX12LMN
        val b = MadeUp.niReg(rnd, space = true)            // e.g. QRZ 4821
        val c = MadeUp.niReg(rnd, space = false)
        val regCells: List<Pair<Cell, String>> = listOf(
            Cell.Text(a) to "RETAIL",
            Cell.Text(a.lowercase().substring(0, 4) + " " + a.lowercase().substring(4)) to "RETAIL",
            Cell.Empty to "RETAIL",
            Cell.Text(b) to "RETAIL",
            Cell.Text(b.replace(" ", "")) to "RETAIL",
            Cell.Number(1234.0) to "RETAIL",
            Cell.Text(a.substring(0, 2) + " " + a.substring(2)) to "RETAIL",
            Cell.Text(MadeUp.shortReg(rnd)) to "RETAIL",
            Cell.Empty to "RETAIL",
            Cell.Text(" " + c.lowercase() + " ") to "DEMO",
            Cell.Text(c) to "DEMO",
            Cell.Text(MadeUp.reg(rnd)) to "DEMO",
            Cell.Empty to "Courtesy",
        )
        val rows = regCells.map { (reg, d) -> vehicleRow(rnd, TrimRules.KEPT, d, reg = reg) }
        return ExportSheet(listOf(headings(TrimRules.KEPT)) + rows)
    }

    /** `heading-case`: headings ` reg number` and `MILEAGE `, matched after trimming and ignoring case. */
    private fun headingCase(rnd: MinStd): ExportSheet {
        val names = listOf("Disposal Method", " reg number", "Veh Description", "MILEAGE ", "Selling Price")
        val rows = List(8) { vehicleRow(rnd, TrimRules.KEPT, rnd.pick(listOf("RETAIL", "DEMO", "Courtesy", "Auction"))) }
        // The rows are made in kept order; put each cell under its heading here.
        val keptIndex = listOf(4, 1, 0, 2, 3)
        return ExportSheet(listOf(headings(names)) + rows.map { r -> keptIndex.map { r[it] } })
    }

    /** `one-row`: one Courtesy row with no reg (4.6, second example). */
    private fun oneRow(rnd: MinStd): ExportSheet =
        ExportSheet(listOf(headings(TrimRules.KEPT), vehicleRow(rnd, TrimRules.KEPT, "Courtesy", reg = Cell.Empty)))

    /** `taken-name`: a few ordinary rows; the case's workbook also has `Trimmed` and `Trimmed (2)` tabs. */
    private fun takenName(rnd: MinStd): ExportSheet {
        val rows = listOf("DEMO", "RETAIL", "Auction", "RETAIL", "Courtesy").map { vehicleRow(rnd, TrimRules.KEPT, it, reg = Cell.Text(MadeUp.reg(rnd))) }
        return ExportSheet(listOf(headings(TrimRules.KEPT)) + rows)
    }

    /** `text-numbers`: `Mileage` and `Selling Price` as text cells (format `@`); listed as the text itself. */
    private fun textNumbers(rnd: MinStd): ExportSheet {
        val rows = List(8) { i ->
            val row = vehicleRow(rnd, TrimRules.KEPT, listOf("RETAIL", "DEMO", "Courtesy", "Auction")[i % 4], reg = Cell.Text(MadeUp.reg(rnd)))
            val miles = rnd.below(120_001)
            val pence = MadeUp.price(rnd)
            val mileText = if (i % 2 == 0) "$miles" else "%,d".format(java.util.Locale.ROOT, miles)
            val priceText = if (i % 3 == 0) "%.2f".format(java.util.Locale.ROOT, pence / 100.0) else "%,.2f".format(java.util.Locale.ROOT, pence / 100.0)
            listOf(row[0], row[1], Cell.Text(mileText, TEXT_FORMAT), Cell.Text(priceText, TEXT_FORMAT), row[4])
        }
        return ExportSheet(listOf(headings(TrimRules.KEPT)) + rows)
    }

    /** `missing`: no `Selling Price`, and `Reg No` instead of `Reg Number`. */
    private fun missing(rnd: MinStd): ExportSheet {
        val names = listOf("Veh Description", "Reg No", "Mileage", "Disposal Method", "Stat")
        val rows = List(3) { vehicleRow(rnd, listOf("Veh Description", "Reg Number", "Mileage", "Disposal Method", "Stat"), "RETAIL") }
        return ExportSheet(listOf(headings(names)) + rows)
    }

    /** `twice`: the Keyloop headings with a second `Reg Number` put in at column K, so it is in H and K. */
    private fun twice(rnd: MinStd): ExportSheet {
        val names = KEYLOOP_HEADINGS.toMutableList().apply { add(10, "Reg Number") }
        val rows = List(5) { vehicleRow(rnd, names, "RETAIL") }
        return ExportSheet(listOf(headings(names)) + rows)
    }

    /** `canary`: `few-columns`' sheet, with an expected listing made wrong on purpose (two regs swapped). */
    private fun canary(few: ExportSheet): TestCase {
        val good = expectedOf(few)
        val lines = good.split('\n').toMutableList()
        // The first two neighbouring vehicle rows whose regs differ.
        val i = (2 until lines.size - 1).first { k ->
            val a = lines[k].split(" | ")
            val b = lines[k + 1].split(" | ")
            a.size == 7 && b.size == 7 && a[2] == TrimRules.TICK_BOX && b[2] == TrimRules.TICK_BOX && a[1] != b[1]
        }
        val a = lines[i].split(" | ").toMutableList()
        val b = lines[i + 1].split(" | ").toMutableList()
        val t = a[1]; a[1] = b[1]; b[1] = t
        lines[i] = a.joinToString(" | ")
        lines[i + 1] = b.joinToString(" | ")
        return TestCase("canary", few, lines.joinToString("\n"))
    }

    // ---- big: generated inside VBA by the same MinStd, mirrored here ------------------------------

    /** The `Disposal Method` values `big` draws from (index by `next() mod size`). */
    val BIG_DISPOSALS = listOf("RETAIL", "DEMO", "Courtesy", "Auction", "Trade", "courtesy")

    /** The reg patterns `big` draws from (index by `next() mod 10`): L letter, l lower-case letter, d digit. */
    val BIG_PATTERNS = listOf("", "LLddLLL", "LLddLLL", "LLddLLL", "LLddLLL", "LLZ dddd", "LLZ dddd", "LLZ dddd", "LLZdddd", "llddlll")

    fun bigSeed(seed: Long): Int = (Math.floorMod(seed, 2147483646L) + 1).toInt()

    /** The rows of `big`, exactly as `Case_12` in the generated file makes them. */
    fun bigRows(seed: Int): List<List<Cell>> {
        val rnd = MinStd(seed.toLong(), raw = true)
        return List(BIG_ROWS) {
            val disposal = BIG_DISPOSALS[rnd.next() % BIG_DISPOSALS.size]
            val pattern = BIG_PATTERNS[rnd.next() % 10]
            val reg = buildString {
                for (ch in pattern) when (ch) {
                    'L' -> append('A' + rnd.next() % 26)
                    'l' -> append('a' + rnd.next() % 26)
                    'd' -> append('0' + rnd.next() % 10)
                    else -> append(ch)
                }
            }
            val mileage = rnd.next() % 120_001
            val pennies = if (rnd.next() % 5 == 0) 0 else 500_000 + rnd.next() % 4_000_001
            listOf(
                Cell.Text(bigDescription(reg, mileage, pennies)),
                if (reg.isEmpty()) Cell.Empty else Cell.Text(reg),
                Cell.Number(mileage.toDouble()),
                Cell.Number(pennies / 100.0, PRICE_FORMAT),
                Cell.Text(disposal),
            )
        }
    }

    /** `big`'s description is built from its row's reg, mileage and price in pence, so the pairing can be checked (4.10.3). */
    fun bigDescription(reg: String, mileage: Int, pennies: Int) = "Car $reg m$mileage p$pennies"

    private fun bigCase(seed: Long): TestCase {
        val s = bigSeed(seed)
        val sheet = ExportSheet(listOf(headings(TrimRules.KEPT)) + bigRows(s))
        val result = TrimRules.trim(sheet) as TrimOutcome.Result
        val expected = buildString {
            for (b in result.blocks) append("BLOCK ").append(b.name).append(' ').append(b.rows.size).append('\n')
            append("ROWS ").append(result.vehicleCount).append('\n')
        }
        return TestCase("big", sheet, expected, bigSeed = s)
    }

    private fun <T> shuffled(items: List<T>, rnd: MinStd): List<T> {
        val a = items.toMutableList()
        for (i in a.lastIndex downTo 1) {
            val j = rnd.below(i + 1)
            val t = a[i]; a[i] = a[j]; a[j] = t
        }
        return a
    }
}

/** Made-up values in the shapes of 4.10.1. */
internal object MadeUp {
    private const val LETTERS = "ABCDEFGHJKLMNOPRSTUVWXY"

    fun letter(rnd: MinStd) = LETTERS[rnd.below(LETTERS.length)]

    fun digits(rnd: MinStd, n: Int) = buildString { repeat(n) { append('0' + rnd.below(10)) } }

    /** `[A-Z]{2}Z [0-9]{4}`-style NI plate, with or without the space. */
    fun niReg(rnd: MinStd, space: Boolean) = "${letter(rnd)}${letter(rnd)}Z" + (if (space) " " else "") + digits(rnd, 4)

    /** `[A-Z]{2}[0-9]{2}[A-Z]{3}` GB plate. */
    fun gbReg(rnd: MinStd) = "${letter(rnd)}${letter(rnd)}${digits(rnd, 2)}${letter(rnd)}${letter(rnd)}${letter(rnd)}"

    /** A dateless, short plate: `ABC 1`. */
    fun shortReg(rnd: MinStd) = "${letter(rnd)}${letter(rnd)}${letter(rnd)} ${1 + rnd.below(999)}"

    fun reg(rnd: MinStd): String = when (rnd.below(10)) {
        0, 1, 2 -> niReg(rnd, space = true)
        3, 4 -> niReg(rnd, space = false)
        9 -> shortReg(rnd)
        else -> gbReg(rnd)
    }

    /** `Model <letter> <engine> <body>`. */
    fun description(rnd: MinStd): String {
        val engine = rnd.pick(listOf("1.0", "1.2", "1.4", "1.5", "1.6", "1.8", "2.0", "2.2"))
        val body = rnd.pick(listOf("Hatch", "Estate", "Saloon", "Van", "5dr", "3dr", "Auto", "Coupe"))
        return "Model ${'A' + rnd.below(26)} $engine $body"
    }

    /** A selling price in pence: 0.00, or 5,000.00 to 45,000.00 (mostly whole pounds). */
    fun price(rnd: MinStd): Int = when {
        rnd.below(6) == 0 -> 0
        rnd.below(4) == 0 -> 500_000 + rnd.below(4_000_001)
        else -> (5_000 + rnd.below(40_001)) * 100
    }

    fun pennies(rnd: MinStd): Int = rnd.below(3_000_001)
}

/**
 * The "minimal standard" generator (Park and Miller), stepped with Schrage's method so every value fits
 * a 32-bit signed number: the same steps run in VBA's `Long` in the generated file's `BigNext`.
 */
class MinStd(seed: Long, raw: Boolean = false) {
    private var s: Int = if (raw) seed.toInt() else (Math.floorMod(seed, 2147483646L) + 1).toInt()

    init {
        require(s in 1..2147483646) { "seed out of range" }
    }

    fun next(): Int {
        val hi = s / 127773
        val lo = s % 127773
        var t = 16807 * lo - 2836 * hi
        if (t <= 0) t += 2147483647
        s = t
        return t
    }

    fun below(n: Int): Int = next() % n

    fun <T> pick(items: List<T>): T = items[below(items.size)]
}
