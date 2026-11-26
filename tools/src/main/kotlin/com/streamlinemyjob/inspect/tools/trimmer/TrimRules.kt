package com.streamlinemyjob.inspect.tools.trimmer

import java.math.BigDecimal

/**
 * The trimmer's reference model (designs/stage-B-trimmer.md 4.3 to 4.8, 4.10.2): export cells in,
 * result listing out. An independent second implementation of the rules the VBA macro follows, so the
 * self-test on the work PC can compare the macro's real result with what these rules expect.
 * Print set-up (4.8), column widths and frozen panes are not part of a listing and are not modelled.
 */
sealed interface Cell {
    /** A cell with nothing in it. */
    data object Empty : Cell

    /** A text cell: written as is, with its number format (not shown in a listing). */
    data class Text(val text: String, val numberFormat: String = GENERAL) : Cell

    /** A number cell: its value and its number format. */
    data class Number(val value: Double, val numberFormat: String = GENERAL) : Cell

    companion object {
        const val GENERAL = "General"
    }
}

/** An export sheet: `rows[0]` is row 1 (the heading row); a row shorter than another is padded with [Cell.Empty]. */
data class ExportSheet(val rows: List<List<Cell>>) {
    fun cell(row: Int, col: Int): Cell = rows.getOrNull(row)?.getOrNull(col) ?: Cell.Empty
}

/** One vehicle row of the export: its five kept cells (in kept-column order) and its export row number (1-based). */
data class VehicleRow(val exportRow: Int, val kept: List<Cell>) {
    val description get() = kept[0]
    val reg get() = kept[1]
    val mileage get() = kept[2]
    val price get() = kept[3]
    val disposal get() = kept[4]
}

data class Block(val name: String, val rows: List<VehicleRow>)

/** What the trim gives: either a result or the message it stops with (nothing made). */
sealed interface TrimOutcome {
    data class Result(val blocks: List<Block>, val vehicleCount: Int) : TrimOutcome {
        fun listing(): String = TrimRules.listing(this)
    }

    data class Message(val text: String) : TrimOutcome
}

object TrimRules {
    /** The kept columns, in result order (2, 4.6). */
    val KEPT = listOf("Veh Description", "Reg Number", "Mileage", "Selling Price", "Disposal Method")

    /** The block names in order; Other is always last and is not in the list (4.5). */
    val BLOCKS = listOf("Retail", "Demo", "Courtesy", "Auction")
    const val OTHER = "Other"
    const val NONE = "none"
    const val TICK_BOX = "[ ]"
    private const val SEP = " | "

    fun trim(sheet: ExportSheet): TrimOutcome {
        val heading = sheet.rows.getOrNull(0).orEmpty()
        val lastCol = heading.indexOfLast { !isEmpty(it) }
        // Columns found by name only (4.3), each kept name with every column that matches it.
        val matches = KEPT.map { name -> (0..lastCol).filter { headingMatches(sheet.cell(0, it), name) } }
        val problems = mutableListOf<String>()
        val missing = KEPT.filterIndexed { i, _ -> matches[i].isEmpty() }
        if (missing.isNotEmpty()) problems += "The export is missing: ${missing.joinToString(", ")}."
        KEPT.forEachIndexed { i, name ->
            val cols = matches[i]
            if (cols.size >= 2) {
                val times = if (cols.size == 2) "twice" else "${cols.size} times"
                problems += "The export has \"$name\" $times (columns ${andList(cols.map(::columnLetter))})."
            }
        }
        if (problems.isNotEmpty()) return TrimOutcome.Message(problems.joinToString(" "))

        val keptCols = matches.map { it.single() }
        val vehicles = (1 until sheet.rows.size)
            .filter { r -> (0..lastCol).any { c -> !isBlank(sheet.cell(r, c)) } }
            .map { r -> VehicleRow(r + 1, keptCols.map { sheet.cell(r, it) }) }
        if (vehicles.isEmpty()) return TrimOutcome.Message("The export has no vehicles under its headings.")

        val byBlock = vehicles.groupBy { blockOf(it.disposal) }
        val blocks = BLOCKS.map { Block(it, order(byBlock[it].orEmpty())) } +
            listOfNotNull(byBlock[OTHER]?.let { Block(OTHER, order(it)) })
        return TrimOutcome.Result(blocks, vehicles.size)
    }

    /** 4.3: trimmed of spaces at both ends, compared without regard to case, equal. */
    fun headingMatches(cell: Cell, name: String): Boolean =
        cellText(cell).trim(' ').equals(name, ignoreCase = true)

    /** 4.5: the block a `Disposal Method` value goes to. */
    fun blockOf(disposal: Cell): String {
        val t = cellText(disposal).trim(' ')
        return BLOCKS.firstOrNull { it.equals(t, ignoreCase = true) } ?: OTHER
    }

    /** 4.5: the reg key: the reg as text, upper case, every space (and tab, and character 160) removed. */
    fun regKey(reg: Cell): String = buildString {
        for (ch in cellText(reg)) if (ch != ' ' && ch != '\t' && ch != ' ') append(ch.uppercaseChar())
    }

    /** 4.5: rows with a reg by reg key (character code order), then rows with no reg; ties keep export order. */
    fun order(rows: List<VehicleRow>): List<VehicleRow> {
        val (withReg, noReg) = rows.partition { regKey(it.reg).isNotEmpty() }
        return withReg.sortedWith { a, b -> regKey(a.reg).compareTo(regKey(b.reg)) } + noReg
    }

    /** 4.10.2: the listing of a result. */
    fun listing(result: TrimOutcome.Result): String {
        val lines = mutableListOf<List<String>>()
        lines += listOf(KEPT[0], KEPT[1], "", KEPT[2], KEPT[3], "", KEPT[4])
        lines += List(7) { "" }
        result.blocks.forEachIndexed { i, block ->
            lines += listOf(block.name) + List(6) { "" }
            if (block.rows.isEmpty()) {
                lines += listOf(NONE) + List(6) { "" }
            } else {
                for (v in block.rows) {
                    lines += listOf(
                        listed(v.description), listed(v.reg), TICK_BOX,
                        listed(v.mileage), listed(v.price), TICK_BOX, listed(v.disposal),
                    )
                }
            }
            if (i != result.blocks.lastIndex) lines += List(7) { "" }
        }
        return buildString {
            for (l in lines) append(l.joinToString(SEP)).append('\n')
            append("ROWS ").append(result.vehicleCount).append('\n')
        }
    }

    /** 4.10.2: one cell as a listing writes it. */
    fun listed(cell: Cell): String = when (cell) {
        Cell.Empty -> ""
        is Cell.Text -> cell.text
        is Cell.Number ->
            if (cell.numberFormat == Cell.GENERAL) shortest(cell.value) else "${shortest(cell.value)} {${cell.numberFormat}}"
    }

    /** The shortest decimal form, `.` as the point, no thousands separator, no exponent. */
    fun shortest(value: Double): String {
        if (value == 0.0) return "0"
        return BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    }

    /** Column letter of a 0-based column index: 0 is A, 25 is Z, 26 is AA. */
    fun columnLetter(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append('A' + rem)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    private fun cellText(cell: Cell): String = when (cell) {
        Cell.Empty -> ""
        is Cell.Text -> cell.text
        is Cell.Number -> shortest(cell.value)
    }

    private fun isEmpty(cell: Cell) = cell == Cell.Empty || (cell is Cell.Text && cell.text.isEmpty())

    /** A cell that is empty or holds only spaces (2, "blank row"). */
    private fun isBlank(cell: Cell) = cell == Cell.Empty || (cell is Cell.Text && cell.text.all { it == ' ' })

    private fun andList(items: List<String>): String =
        if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + " and " + items.last()
}
