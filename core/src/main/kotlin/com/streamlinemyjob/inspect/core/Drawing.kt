package com.streamlinemyjob.inspect.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * One problem in a drawing file or a link file (designs/stage-1-foundation.md 4.20, 4.22): a stable
 * code, the JSON path of the problem (for example `regions[3].points[1]`; empty for the whole file)
 * and a detail. Same shape as [SeedError].
 */
data class FileError(val code: String, val path: String, val detail: String) {
    override fun toString(): String = buildString {
        append(code)
        if (path.isNotEmpty()) append(' ').append(path)
        if (detail.isNotEmpty()) append(" (").append(detail).append(')')
    }
}

/** A point in drawing units; every coordinate in a drawing file is a whole number (4.20). */
data class Pt(val x: Int, val y: Int)

/** A region: a part of the drawing you can tap. [points] is a simple polygon, closed from the last point to the first. */
data class Region(val id: RegionId, val anchor: Pt, val points: List<Pt>)

/** A plain line the app draws and nobody can tap (4.20 "Decor"). */
data class Decor(val points: List<Pt>, val closed: Boolean)

/** One of the drawing's fixed texts, drawn centred on [at]. [text] is the cleaned text (4.7). */
data class DrawingText(val text: String, val at: Pt)

/**
 * A valid drawing (4.20). [regions] are in priority order: where regions overlap, a point belongs to
 * the first one in the list that contains it.
 */
data class Drawing(
    val id: String,
    val width: Int,
    val height: Int,
    val regions: List<Region>,
    val decor: List<Decor>,
    val texts: List<DrawingText>,
) {
    companion object {
        const val FORMAT = "smj-drawing"
        const val VERSION = "1"
        const val SIZE_MIN = 100
        const val SIZE_MAX = 10_000

        /** `DRAWING_GAP`: two regions closer than this many drawing units are refused (about 2 mm on the owner's phone, 4.20). */
        const val GAP_MIN = 32.0

        /** `DRAWING_SMALL`: 16 points at this distance, in drawing units, from the anchor (angles 0°, 22.5°, … 337.5°). */
        const val SMALL_RADIUS = 20.0
        const val SMALL_POINTS = 16

        private val TOP_FIELDS = listOf("format", "version", "id", "width", "height", "regions", "decor", "texts")
        private val REGION_FIELDS = listOf("id", "anchor", "points")
        private val DECOR_FIELDS = listOf("points", "closed")
        private val TEXT_FIELDS = listOf("text", "at")

        sealed interface Parsed
        data class Valid(val drawing: Drawing) : Parsed
        data class Invalid(val errors: List<FileError>) : Parsed

        /** Every error in [text]; empty when the drawing is valid. */
        fun validate(text: String): List<FileError> = when (val p = parse(text)) {
            is Valid -> emptyList()
            is Invalid -> p.errors
        }

        /** Parses and validates [text] (4.20's table). All errors are collected, each with its code and JSON path. */
        fun parse(text: String): Parsed {
            val root: JsonElement = try {
                Json.parseToJsonElement(text)
            } catch (e: Exception) {
                return Invalid(listOf(FileError(Codes.DRAWING_JSON, "", e.message?.lineSequence()?.firstOrNull()?.trim() ?: e.javaClass.simpleName)))
            }
            val v = Validation()
            val drawing = v.file(root as? JsonObject ?: JsonObject(emptyMap()))
            return if (v.errors.isEmpty() && drawing != null) Valid(drawing) else Invalid(v.errors)
        }

        private class Validation {
            val errors = mutableListOf<FileError>()
            private var width: Int? = null
            private var height: Int? = null

            private fun error(code: String, path: String, detail: String) {
                errors += FileError(code, path, detail)
            }

            private fun join(path: String, field: String) = if (path.isEmpty()) field else "$path.$field"

            /** Unknown fields, then missing ones, as `DRAWING_FIELD <path> (unknown|missing)`. */
            private fun fields(obj: JsonObject, path: String, expected: List<String>, reportMissing: Collection<String> = expected) {
                obj.keys.filter { it !in expected }.forEach { error(Codes.DRAWING_FIELD, join(path, it), "unknown") }
                reportMissing.filter { it !in obj }.forEach { error(Codes.DRAWING_FIELD, join(path, it), "missing") }
            }

            private fun string(obj: JsonObject, path: String, field: String): String? {
                val e = obj[field] ?: return null
                if (e is JsonPrimitive && e.isString) return e.content
                error(Codes.DRAWING_FIELD, join(path, field), "not a string: $e")
                return null
            }

            private fun array(obj: JsonObject, path: String, field: String): JsonArray? {
                val e = obj[field] ?: return null
                if (e is JsonArray) return e
                error(Codes.DRAWING_FIELD, join(path, field), "not an array: $e")
                return null
            }

            private fun objectAt(e: JsonElement, path: String): JsonObject? {
                if (e is JsonObject) return e
                error(Codes.DRAWING_FIELD, path, "not an object: $e")
                return null
            }

            fun file(obj: JsonObject): Drawing? {
                val format = obj["format"]
                if (!(format is JsonPrimitive && format.isString && format.content == FORMAT)) {
                    error(Codes.DRAWING_FORMAT, "format", "expected \"$FORMAT\", found ${format ?: "nothing"}")
                }
                val version = obj["version"]
                if (!(version is JsonPrimitive && !version.isString && version.content == VERSION)) {
                    error(Codes.DRAWING_FORMAT, "version", "expected $VERSION, found ${version ?: "nothing"}")
                }
                // format and version are reported as DRAWING_FORMAT; a missing decor is DRAWING_DECOR (below).
                fields(obj, "", TOP_FIELDS, reportMissing = listOf("id", "width", "height", "regions", "texts"))

                val id = string(obj, "", "id")
                if (id != null && !Rules.SEED_ID.matches(id)) error(Codes.DRAWING_ID_BAD, "id", JsonPrimitive(id).toString())
                width = size(obj, "width")
                height = size(obj, "height")

                val regionsJson = array(obj, "", "regions")
                val regions = regionsJson?.mapIndexed { i, e -> region(e, "regions[$i]") }
                if (regionsJson != null && regionsJson.isEmpty()) error(Codes.DRAWING_NO_REGIONS, "regions", "no regions")
                regions?.let { ids(it, regionsJson) }

                val decorJson = decorArray(obj)
                val decor = decorJson?.mapIndexed { i, e -> decor(e, "decor[$i]") }

                val textsJson = array(obj, "", "texts")
                val texts = textsJson?.mapIndexed { i, e -> text(e, "texts[$i]") }

                geometry(regions)

                val w = width
                val h = height
                if (errors.isNotEmpty() || id == null || w == null || h == null || regions == null || decor == null || texts == null) return null
                return Drawing(id, w, h, regions.filterNotNull(), decor.filterNotNull(), texts.filterNotNull())
            }

            private fun size(obj: JsonObject, field: String): Int? {
                val e = obj[field] ?: return null
                val n = whole(e)
                if (n == null || n < SIZE_MIN || n > SIZE_MAX) {
                    error(Codes.DRAWING_SIZE, field, "expected a whole number from $SIZE_MIN to $SIZE_MAX, found $e")
                    return null
                }
                return n.toInt()
            }

            /** A JSON number written as a whole number (no fraction, no exponent). */
            private fun whole(e: JsonElement?): Long? = if (e is JsonPrimitive && !e.isString) e.content.toLongOrNull() else null

            /**
             * A point `[x, y]` of whole numbers inside the frame (when the frame is known), else null
             * with one error of [code] at [path].
             */
            private fun point(e: JsonElement?, path: String, code: String): Pt? {
                val pair = e as? JsonArray
                if (pair == null || pair.size != 2) {
                    error(code, path, "expected [x, y], found ${e ?: "nothing"}")
                    return null
                }
                val x = whole(pair[0])
                val y = whole(pair[1])
                if (x == null || y == null) {
                    error(code, path, "coordinates must be whole numbers, found $e")
                    return null
                }
                val w = width
                val h = height
                if (w != null && h != null && (x < 0 || x > w || y < 0 || y > h)) {
                    error(code, path, "($x, $y) is outside the frame 0..$w, 0..$h")
                    return null
                }
                if (x < Int.MIN_VALUE || x > Int.MAX_VALUE || y < Int.MIN_VALUE || y > Int.MAX_VALUE) {
                    error(code, path, "($x, $y) is out of range")
                    return null
                }
                return Pt(x.toInt(), y.toInt())
            }

            private fun region(e: JsonElement, path: String): Region? {
                val obj = objectAt(e, path) ?: return null
                fields(obj, path, REGION_FIELDS)
                val id = string(obj, path, "id")
                if (id != null && !Rules.SEED_ID.matches(id)) error(Codes.DRAWING_ID_BAD, "$path.id", JsonPrimitive(id).toString())
                val anchor = obj["anchor"]?.let { point(it, "$path.anchor", Codes.DRAWING_POINTS) }
                val pointsJson = array(obj, path, "points")
                var ok = pointsJson != null
                val points = pointsJson?.mapIndexed { i, p -> point(p, "$path.points[$i]", Codes.DRAWING_POINTS).also { if (it == null) ok = false } }
                if (pointsJson != null && pointsJson.size < 3) {
                    error(Codes.DRAWING_POINTS, "$path.points", "a region needs 3 or more points, found ${pointsJson.size}")
                    ok = false
                }
                if (id == null || anchor == null || points == null || !ok) return null
                return Region(id, anchor, points.filterNotNull())
            }

            /** `DRAWING_ID_DUP`: the error is on the later region and names the first. */
            private fun ids(regions: List<Region?>, json: JsonArray?) {
                val first = HashMap<String, String>()
                json?.forEachIndexed { i, e ->
                    val id = ((e as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEachIndexed
                    val earlier = first.putIfAbsent(id, "regions[$i].id")
                    if (earlier != null) error(Codes.DRAWING_ID_DUP, "regions[$i].id", "$id, also at $earlier")
                }
                check(regions.size == (json?.size ?: 0))
            }

            private fun decorArray(obj: JsonObject): JsonArray? {
                val e = obj["decor"]
                if (e == null) {
                    error(Codes.DRAWING_DECOR, "decor", "missing")
                    return null
                }
                if (e is JsonArray) return e
                error(Codes.DRAWING_DECOR, "decor", "not an array: $e")
                return null
            }

            private fun decor(e: JsonElement, path: String): Decor? {
                val obj = objectAt(e, path) ?: return null
                fields(obj, path, DECOR_FIELDS, reportMissing = emptyList())
                val pointsJson = obj["points"] as? JsonArray
                var ok = true
                if (pointsJson == null) {
                    error(Codes.DRAWING_DECOR, "$path.points", "missing or not an array")
                    ok = false
                }
                val points = pointsJson?.mapIndexed { i, p -> point(p, "$path.points[$i]", Codes.DRAWING_DECOR).also { if (it == null) ok = false } }
                if (pointsJson != null && pointsJson.size < 2) {
                    error(Codes.DRAWING_DECOR, "$path.points", "a line needs 2 or more points, found ${pointsJson.size}")
                    ok = false
                }
                val closed = obj["closed"]
                val closedValue = if (closed is JsonPrimitive && !closed.isString) closed.content.takeIf { it == "true" || it == "false" } else null
                if (closedValue == null) {
                    error(Codes.DRAWING_DECOR, "$path.closed", "expected true or false, found ${closed ?: "nothing"}")
                    ok = false
                }
                if (!ok || points == null) return null
                return Decor(points.filterNotNull(), closedValue == "true")
            }

            private fun text(e: JsonElement, path: String): DrawingText? {
                val obj = objectAt(e, path) ?: return null
                fields(obj, path, TEXT_FIELDS)
                val raw = string(obj, path, "text")
                val name = raw?.let { Text.checkName(it) }
                name?.problems?.forEach { p ->
                    val code = "DRAWING_TEXT_" + p.code.removePrefix("NAME_")
                    val detail = if (p.code == Codes.NAME_TOO_LONG) "${p.detail} code points, at most ${Rules.NAME_MAX}" else p.detail
                    error(code, "$path.text", detail)
                }
                val at = obj["at"]?.let { point(it, "$path.at", Codes.DRAWING_POINTS) }
                if (name == null || !name.ok || at == null) return null
                return DrawingText(name.cleaned, at)
            }

            /**
             * The checks that need the frame and the shapes: `DRAWING_SELF_CROSS`, `DRAWING_ANCHOR`,
             * `DRAWING_GAP` and `DRAWING_SMALL`. Each region that has valid points is checked; a region that does not is left out
             * of the resolving (its own errors are reported already).
             */
            private fun geometry(regions: List<Region?>?) {
                val w = width ?: return
                val h = height ?: return
                if (regions == null) return
                val valid = regions.filterNotNull()
                for (j in regions.indices) {
                    val later = regions[j] ?: continue
                    for (i in 0 until j) {
                        val earlier = regions[i] ?: continue
                        val d = distance(earlier, later)
                        if (d < GAP_MIN) {
                            error(Codes.DRAWING_GAP, "regions[$j]", "${earlier.id} and ${later.id} are ${Math.floor(d).toLong()} units apart, at least ${GAP_MIN.toInt()} are needed")
                        }
                    }
                }
                regions.forEachIndexed { i, r ->
                    if (r == null) return@forEachIndexed
                    val path = "regions[$i]"
                    selfCross(r.points)?.let { (a, b) -> error(Codes.DRAWING_SELF_CROSS, path, "edge $a and edge $b touch or cross") }
                    val at = Hit.resolveIn(w, h, valid, r.anchor.x.toDouble(), r.anchor.y.toDouble())
                    if (at != r.id) {
                        error(Codes.DRAWING_ANCHOR, "$path.anchor", "the anchor (${r.anchor.x}, ${r.anchor.y}) resolves to ${at ?: "nothing"}, not to ${r.id}")
                        return@forEachIndexed
                    }
                    for (k in 0 until SMALL_POINTS) {
                        val angle = 2.0 * PI * k / SMALL_POINTS
                        val px = r.anchor.x + SMALL_RADIUS * cos(angle)
                        val py = r.anchor.y + SMALL_RADIUS * sin(angle)
                        val hit = Hit.resolveIn(w, h, valid, px, py)
                        if (hit != r.id) {
                            error(Codes.DRAWING_SMALL, path, "the point $SMALL_RADIUS units from the anchor at ${k * 360.0 / SMALL_POINTS} degrees resolves to ${hit ?: "nothing"}, not to ${r.id}")
                            break
                        }
                    }
                }
            }
        }

        /**
         * The distance between two regions (4.20, `DRAWING_GAP`): 0 when an edge of one touches or crosses an
         * edge of the other, or one region contains a corner of the other (by 4.21's contains rule, so one
         * region wholly inside another is 0); otherwise the smallest distance from a corner of either to an
         * edge of the other.
         */
        internal fun distance(a: Region, b: Region): Double {
            val pa = a.points
            val pb = b.points
            for (i in pa.indices) for (j in pb.indices) {
                if (segmentsTouch(pa[i], pa[(i + 1) % pa.size], pb[j], pb[(j + 1) % pb.size])) return 0.0
            }
            if (pa.any { Hit.contains(pb, it.x.toDouble(), it.y.toDouble()) } || pb.any { Hit.contains(pa, it.x.toDouble(), it.y.toDouble()) }) return 0.0
            var best = Double.MAX_VALUE
            for (p in pa) for (j in pb.indices) best = minOf(best, pointToSegment(p, pb[j], pb[(j + 1) % pb.size]))
            for (p in pb) for (i in pa.indices) best = minOf(best, pointToSegment(p, pa[i], pa[(i + 1) % pa.size]))
            return best
        }

        private fun pointToSegment(p: Pt, a: Pt, b: Pt): Double {
            val dx = (b.x - a.x).toDouble()
            val dy = (b.y - a.y).toDouble()
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
            return Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
        }

        /** The first pair of edges of [points] that are not next to each other and touch or cross, as edge numbers; null if none. */
        internal fun selfCross(points: List<Pt>): Pair<Int, Int>? {
            val n = points.size
            for (i in 0 until n) {
                for (j in i + 2 until n) {
                    if (i == 0 && j == n - 1) continue // the last edge is next to the first
                    if (segmentsTouch(points[i], points[(i + 1) % n], points[j], points[(j + 1) % n])) return i to j
                }
            }
            return null
        }

        private fun side(a: Pt, b: Pt, c: Pt): Int {
            val cross = (b.x - a.x).toLong() * (c.y - a.y) - (b.y - a.y).toLong() * (c.x - a.x)
            return cross.compareTo(0L)
        }

        private fun within(a: Pt, b: Pt, c: Pt): Boolean =
            c.x >= minOf(a.x, b.x) && c.x <= maxOf(a.x, b.x) && c.y >= minOf(a.y, b.y) && c.y <= maxOf(a.y, b.y)

        /** Whether segment [p1]-[p2] and segment [p3]-[p4] share a point (touching counts). Whole-number arithmetic, exact. */
        private fun segmentsTouch(p1: Pt, p2: Pt, p3: Pt, p4: Pt): Boolean {
            val d1 = side(p3, p4, p1)
            val d2 = side(p3, p4, p2)
            val d3 = side(p1, p2, p3)
            val d4 = side(p1, p2, p4)
            if (d1 * d2 < 0 && d3 * d4 < 0) return true
            return (d1 == 0 && within(p3, p4, p1)) || (d2 == 0 && within(p3, p4, p2)) ||
                (d3 == 0 && within(p1, p2, p3)) || (d4 == 0 && within(p1, p2, p4))
        }
    }
}
