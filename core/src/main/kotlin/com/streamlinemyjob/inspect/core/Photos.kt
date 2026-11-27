package com.streamlinemyjob.inspect.core

import java.time.LocalDate
import java.time.LocalTime

/**
 * Photo rules (designs/stage-2-camera.md 4.2, 4.3, 4.8, 4.12, 4.13): the constants, the step-down plan
 * for encoding a shot, size text, the stamp's text and fitting rule, and photo folder names.
 * Pure Kotlin with no Android, so every rule here is tested on the JVM; the bitmap work is `PhotoEncoder`.
 */
object Photos {
    // 4.2: the only place these numbers live. Tests write the design's numbers as literals.
    const val LONG_SIDE = 2000
    const val START_QUALITY = 85
    const val MIN_QUALITY = 60
    const val QUALITY_STEP = 5
    const val SCALE_STEP = 0.85
    const val MIN_LONG_SIDE = 800
    const val MAX_BYTES = 1_000_000
    const val MAX_PER_FINDING = 20
    const val LOW_SPACE_BYTES = 500_000_000L
    const val CAPTURE_WIDTH = 2048
    const val CAPTURE_HEIGHT = 1536
    const val STAMP_BAND = 0.045

    /** The code a refused photo carries (4.3 step 4); the screens show "The photo could not be saved. Try again." */
    const val PHOTO_TOO_BIG = "PHOTO_TOO_BIG"

    sealed interface PlanResult

    /** The try that fit: [longSide] and [quality], [reduced] by 4.3 step 5, and how many tries it took. */
    data class Kept(val longSide: Int, val quality: Int, val reduced: Boolean, val tries: Int) : PlanResult

    /** Nothing fitted down to [MIN_LONG_SIDE]; [tries] encodes were made. */
    data class TooBig(val tries: Int) : PlanResult

    /**
     * The target long side for an upright [width] x [height] image (4.3 step 2): [LONG_SIDE], never scaled up.
     */
    fun targetLongSide(width: Int, height: Int): Int = minOf(LONG_SIDE, maxOf(width, height))

    /** The size of an upright [width] x [height] image scaled to long side [longSide] (4.3 step 2). */
    fun scaledSize(width: Int, height: Int, longSide: Int): Pair<Int, Int> {
        val long = maxOf(width, height)
        val short = minOf(width, height)
        val shortSide = Math.round(short.toDouble() * longSide / long).toInt()
        return if (width >= height) longSide to shortSide else shortSide to longSide
    }

    /**
     * Steps 2 to 5 of 4.3 for an upright [width] x [height] image. [encode] returns the byte count of
     * the stored JPEG for a long side and a quality (the caller scales, stamps and compresses).
     * The first result of at most [MAX_BYTES] is kept.
     */
    fun plan(width: Int, height: Int, encode: (longSide: Int, quality: Int) -> Int): PlanResult {
        val target = targetLongSide(width, height)
        var tries = 0
        var quality = START_QUALITY
        while (quality >= MIN_QUALITY) {
            tries++
            if (encode(target, quality) <= MAX_BYTES) {
                return Kept(target, quality, reduced = quality < START_QUALITY, tries = tries)
            }
            quality -= QUALITY_STEP
        }
        var longSide = target
        while (true) {
            longSide = Math.round(longSide * SCALE_STEP).toInt()
            if (longSide < MIN_LONG_SIDE) return TooBig(tries)
            tries++
            if (encode(longSide, MIN_QUALITY) <= MAX_BYTES) {
                return Kept(longSide, MIN_QUALITY, reduced = true, tries = tries)
            }
        }
    }

    /**
     * A size as text (4.8): decimal. Under 1,000 MB "x.y MB" (half-up), from 1,000 MB "x.yz GB" (half-up);
     * a size that rounds to 1,000.0 MB is shown in GB.
     */
    fun formatBytes(bytes: Long): String {
        require(bytes >= 0) { "bytes must not be negative" }
        val tenthsOfMb = halfUp(bytes, 100_000L)
        if (tenthsOfMb < 10_000L) return "${tenthsOfMb / 10}.${tenthsOfMb % 10} MB"
        val hundredthsOfGb = halfUp(bytes, 10_000_000L)
        return "${hundredthsOfGb / 100}.${(hundredthsOfGb % 100).toString().padStart(2, '0')} GB"
    }

    private fun halfUp(value: Long, divisor: Long): Long = (value + divisor / 2) / divisor

    /**
     * The stamp's text (4.13): `<reg display> · <inspection date DD/MM/YYYY> · <area list name> › <area name>`.
     * [regDisplay] is `Reg.display`; the names are as the finding has them when the photo is saved.
     */
    fun stampText(regDisplay: String, inspectionDate: LocalDate, areaListName: String, areaName: String): String =
        stampText(regDisplay, inspectionDate, areaListName, areaName, 0, false)

    private fun stampText(
        regDisplay: String,
        inspectionDate: LocalDate,
        areaListName: String,
        areaName: String,
        keepCodePoints: Int,
        cut: Boolean,
    ): String {
        val shownArea = if (cut) areaName.codePointPrefix(keepCodePoints) + ELLIPSIS else areaName
        return "$regDisplay$SEP${Dates.format(inspectionDate)}$SEP$areaListName$ARROW$shownArea"
    }

    /** What to draw: the [text] and the font size in pixels. */
    data class StampFit(val text: String, val fontSize: Int)

    /** Strip height in pixels for a scaled image of the given short side (4.13): `max(24, round(0.045 x short))`. */
    fun stampBandHeight(shortSide: Int): Int = maxOf(24, Math.round(STAMP_BAND * shortSide).toInt())

    /** Left (and right) padding of the stamp text: `round(0.4 x B)`. */
    fun stampPadding(band: Int): Int = Math.round(0.4 * band).toInt()

    /**
     * The fitting rule of 4.13. The font starts at `round(0.6 x B)` and shrinks one pixel at a time to
     * `round(0.4 x B)` until the text is no wider than [availableWidth] (the image width less twice the
     * padding). If it still does not fit, the area name is cut from its end and `…` added until it fits
     * (at the smallest font); at worst the area name is just `…`. The reg, date and area list name are
     * never cut. [measure] gives the width in pixels of a text at a font size.
     */
    fun fitStamp(
        regDisplay: String,
        inspectionDate: LocalDate,
        areaListName: String,
        areaName: String,
        band: Int,
        availableWidth: Float,
        measure: (text: String, fontSize: Int) -> Float,
    ): StampFit {
        val full = stampText(regDisplay, inspectionDate, areaListName, areaName)
        val start = Math.round(0.6 * band).toInt()
        val smallest = Math.round(0.4 * band).toInt()
        var size = start
        while (size >= smallest) {
            if (measure(full, size) <= availableWidth) return StampFit(full, size)
            size--
        }
        var keep = areaName.codePointCount(0, areaName.length) - 1
        while (keep >= 0) {
            val text = stampText(regDisplay, inspectionDate, areaListName, areaName, keep, true)
            if (measure(text, smallest) <= availableWidth) return StampFit(text, smallest)
            keep--
        }
        // Even `…` alone does not fit: the reg and date are never cut, so draw it anyway.
        return StampFit(stampText(regDisplay, inspectionDate, areaListName, areaName, 0, true), smallest)
    }

    /** The subfolder for a reg (4.12, C15): the reg key itself (A-Z and 0-9 only, so safe as a folder name). */
    fun subfolder(regKey: String): String = regKey

    /**
     * The photo's file name (4.12, C16): `<reg key> <inspection date yyyy-mm-dd> <area name> <HHmmss>.jpg`.
     * The area name is cleaned: each of `/ \ : * ? " < > |` and every control character becomes `-`
     * (whitespace, which includes tab and newline, is first collapsed to one space), trimmed, cut to
     * 40 code points, `area` if empty. If the name is [taken] in the subfolder, ` 2`, ` 3`, ... is added
     * before `.jpg`.
     */
    fun fileName(
        regKey: String,
        inspectionDate: LocalDate,
        areaName: String,
        savedAt: LocalTime,
        taken: (String) -> Boolean = { false },
    ): String {
        val time = "%02d%02d%02d".format(savedAt.hour, savedAt.minute, savedAt.second)
        val base = "$regKey ${Dates.toStored(inspectionDate)} ${cleanArea(areaName)} $time"
        var name = "$base.jpg"
        var n = 2
        while (taken(name)) {
            name = "$base $n.jpg"
            n++
        }
        return name
    }

    /** The relative path of a photo in the photo folder: `<subfolder>/<file name>`. */
    fun relPath(regKey: String, fileName: String): String = "${subfolder(regKey)}/$fileName"

    private val REL_PATH_FORM = Regex("""^([A-Z0-9]{2,10})/\1 (\d{4}-\d{2}-\d{2}) (.+) \d{6}( \d+)?\.jpg$""")

    /**
     * Whether [relPath] is of 4.12's form, for `IC_PHOTO_FIELDS` (4.6): `<subfolder>/<file name>` where the subfolder is a
     * reg key (A-Z and 0-9, 2 to 10 characters) and the file name is `<that reg key> <valid yyyy-mm-dd> <area name> <HHmmss>[ n].jpg`
     * with no character that [fileName] turns into `-` in the area name part. The reg is not compared with the vehicle's current
     * reg: the reg can change after the photo is taken and the path is kept (C16).
     */
    fun isRelPathForm(relPath: String): Boolean {
        val m = REL_PATH_FORM.matchEntire(relPath) ?: return false
        try {
            Dates.fromStored(m.groupValues[2])
        } catch (e: Exception) {
            return false
        }
        val area = m.groupValues[3]
        var i = 0
        while (i < area.length) {
            val cp = area.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 0x10000 && cp.toChar() in BAD_NAME_CHARS || Character.getType(cp) == Character.CONTROL.toInt()) return false
        }
        return true
    }

    private const val AREA_MAX = 40
    private const val BAD_NAME_CHARS = "/\\:*?\"<>|"

    private fun cleanArea(area: String): String {
        val sb = StringBuilder()
        var inSpace = false
        var i = 0
        while (i < area.length) {
            val cp = area.codePointAt(i)
            i += Character.charCount(cp)
            if (Text.isWhitespace(cp)) {
                if (!inSpace) sb.append(' ')
                inSpace = true
                continue
            }
            inSpace = false
            if (cp < 0x10000 && cp.toChar() in BAD_NAME_CHARS || Character.getType(cp) == Character.CONTROL.toInt()) {
                sb.append('-')
            } else {
                sb.appendCodePoint(cp)
            }
        }
        val trimmed = sb.toString().trim(' ')
        val cut = trimmed.codePointPrefix(AREA_MAX)
        return cut.ifEmpty { "area" }
    }

    private fun String.codePointPrefix(count: Int): String {
        if (count <= 0) return ""
        if (codePointCount(0, length) <= count) return this
        return substring(0, offsetByCodePoints(0, count))
    }

    private const val SEP = " · "
    private const val ARROW = " › "
    private const val ELLIPSIS = "…"
}
