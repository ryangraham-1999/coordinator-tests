package com.streamlinemyjob.inspect.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One starting link: the area value a region picks on a fresh install (4.22). */
data class Link(val region: RegionId, val value: String)

/** A valid link file: the drawing it is for and its links in file order. A region with no entry starts not linked. */
data class LinkData(val drawing: String, val links: List<Link>)

/**
 * The link file, format version 1: parser and validator (4.22), against one seed and one drawing.
 * All errors are collected, each with its code and JSON path.
 */
object Links {
    const val FORMAT = "smj-links"
    const val VERSION = "1"

    private val TOP_FIELDS = listOf("format", "version", "drawing", "links")
    private val LINK_FIELDS = listOf("region", "value")

    sealed interface Parsed
    data class Valid(val links: LinkData) : Parsed
    data class Invalid(val errors: List<FileError>) : Parsed

    /** Every error in [text] against [seed] and [drawing]; empty when the link file is valid. */
    fun validate(text: String, seed: SeedData, drawing: Drawing): List<FileError> = when (val p = parse(text, seed, drawing)) {
        is Valid -> emptyList()
        is Invalid -> p.errors
    }

    fun parse(text: String, seed: SeedData, drawing: Drawing): Parsed {
        val root: JsonElement = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return Invalid(listOf(FileError(Codes.LINKS_JSON, "", e.message?.lineSequence()?.firstOrNull()?.trim() ?: e.javaClass.simpleName)))
        }
        val v = Validation(seed, drawing)
        val data = v.file(root as? JsonObject ?: JsonObject(emptyMap()))
        return if (v.errors.isEmpty() && data != null) Valid(data) else Invalid(v.errors)
    }

    private class Validation(seed: SeedData, private val drawing: Drawing) {
        val errors = mutableListOf<FileError>()
        private val regionIds = drawing.regions.map { it.id }.toHashSet()
        private val roleOfValue: Map<String, Role> = seed.lists.flatMap { list -> list.values.map { it.id to list.role } }.toMap()
        private val firstEntryOfRegion = HashMap<String, String>()

        private fun error(code: String, path: String, detail: String) {
            errors += FileError(code, path, detail)
        }

        private fun join(path: String, field: String) = if (path.isEmpty()) field else "$path.$field"

        private fun fields(obj: JsonObject, path: String, expected: List<String>, reportMissing: Collection<String> = expected) {
            obj.keys.filter { it !in expected }.forEach { error(Codes.LINKS_FIELD, join(path, it), "unknown") }
            reportMissing.filter { it !in obj }.forEach { error(Codes.LINKS_FIELD, join(path, it), "missing") }
        }

        private fun string(obj: JsonObject, path: String, field: String): String? {
            val e = obj[field] ?: return null
            if (e is JsonPrimitive && e.isString) return e.content
            error(Codes.LINKS_FIELD, join(path, field), "not a string: $e")
            return null
        }

        fun file(obj: JsonObject): LinkData? {
            val format = obj["format"]
            if (!(format is JsonPrimitive && format.isString && format.content == FORMAT)) {
                error(Codes.LINKS_FORMAT, "format", "expected \"$FORMAT\", found ${format ?: "nothing"}")
            }
            val version = obj["version"]
            if (!(version is JsonPrimitive && !version.isString && version.content == VERSION)) {
                error(Codes.LINKS_FORMAT, "version", "expected $VERSION, found ${version ?: "nothing"}")
            }
            fields(obj, "", TOP_FIELDS, reportMissing = listOf("drawing", "links"))
            val drawingId = string(obj, "", "drawing")
            if (drawingId != null && drawingId != drawing.id) {
                error(Codes.LINKS_DRAWING, "drawing", "$drawingId, the drawing is ${drawing.id}")
            }
            val linksJson = obj["links"]?.let {
                it as? JsonArray ?: run {
                    error(Codes.LINKS_FIELD, "links", "not an array: $it")
                    null
                }
            }
            val links = linksJson?.mapIndexed { i, e -> link(e, "links[$i]") }
            if (errors.isNotEmpty() || drawingId == null || links == null || links.any { it == null }) return null
            return LinkData(drawingId, links.filterNotNull())
        }

        private fun link(e: JsonElement, path: String): Link? {
            val obj = e as? JsonObject ?: run {
                error(Codes.LINKS_FIELD, path, "not an object: $e")
                return null
            }
            fields(obj, path, LINK_FIELDS)
            val region = string(obj, path, "region")
            val value = string(obj, path, "value")
            if (region != null) {
                if (region !in regionIds) {
                    error(Codes.LINKS_REGION_UNKNOWN, "$path.region", "$region is not a region of ${drawing.id}")
                } else {
                    val earlier = firstEntryOfRegion.putIfAbsent(region, "$path.region")
                    if (earlier != null) error(Codes.LINKS_REGION_DUP, "$path.region", "$region, also at $earlier")
                }
            }
            if (value != null) {
                val role = roleOfValue[value]
                when {
                    role == null -> error(Codes.LINKS_VALUE_UNKNOWN, "$path.value", "$value is not a value in the seed")
                    role != Role.AREA -> error(Codes.LINKS_VALUE_ROLE, "$path.value", "$value is in a ${role.name} list, not an AREA list")
                }
            }
            if (region == null || value == null) return null
            return Link(region, value)
        }
    }
}
