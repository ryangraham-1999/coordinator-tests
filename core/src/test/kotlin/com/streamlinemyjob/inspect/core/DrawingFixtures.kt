package com.streamlinemyjob.inspect.core

import java.io.File

/** Shared by the drawing tests: the confirmed files, and small drawing fixtures written for the tests (designs/stage-1-foundation.md 9). */
object DrawingFixtures {
    val confirmedDrawingText: String by lazy { File(System.getProperty("smj.drawingFile") ?: error("smj.drawingFile not set")).readText() }
    val confirmedLinksText: String by lazy { File(System.getProperty("smj.linksFile") ?: error("smj.linksFile not set")).readText() }
    val confirmedSeedText: String by lazy { File(System.getProperty("smj.seedFile") ?: error("smj.seedFile not set")).readText() }

    val confirmedDrawing: Drawing by lazy { (Drawing.parse(confirmedDrawingText) as Drawing.Companion.Valid).drawing }
    val confirmedSeed: SeedData by lazy { (Seed.parse(confirmedSeedText) as Seed.Valid).seed }

    fun square(x: Int, y: Int, size: Int): String =
        "[[$x,$y],[${x + size},$y],[${x + size},${y + size}],[$x,${y + size}]]"

    fun region(id: String, points: String, anchor: String): String = """{"id":"$id","anchor":$anchor,"points":$points}"""

    /** Two valid squares, `a` at 100..300 and `b` at 500..700, with every part overridable so a fixture can break exactly one thing. */
    fun drawing(
        format: String = "\"smj-drawing\"",
        version: String = "1",
        id: String = "\"t-1\"",
        width: String = "1000",
        height: String = "1000",
        regions: String = region("a", square(100, 100, 200), "[200,200]") + "," + region("b", square(500, 100, 200), "[600,200]"),
        decor: String? = """[{"points":[[0,0],[10,10]],"closed":false}]""",
        texts: String = """[{"text":"FRONT","at":[500,50]}]""",
        extra: String = "",
    ): String = buildString {
        append("{\"format\":$format,\"version\":$version,\"id\":$id,\"width\":$width,\"height\":$height,")
        append("\"regions\":[$regions],")
        if (decor != null) append("\"decor\":$decor,")
        append("\"texts\":$texts$extra}")
    }

    fun parse(text: String): Drawing = (Drawing.parse(text) as Drawing.Companion.Valid).drawing
}

/** The test's own rule for "inside" (whole-number arithmetic): on an edge counts, else the winding number is not zero. Independent of [Hit]. */
object Winding {
    fun inside(points: List<Pt>, px: Int, py: Int): Boolean {
        var wn = 0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val left = (b.x - a.x).toLong() * (py - a.y) - (px - a.x).toLong() * (b.y - a.y)
            if (left == 0L && px >= minOf(a.x, b.x) && px <= maxOf(a.x, b.x) && py >= minOf(a.y, b.y) && py <= maxOf(a.y, b.y)) return true
            if (a.y <= py) {
                if (b.y > py && left > 0) wn++
            } else if (b.y <= py && left < 0) wn--
        }
        return wn != 0
    }
}
