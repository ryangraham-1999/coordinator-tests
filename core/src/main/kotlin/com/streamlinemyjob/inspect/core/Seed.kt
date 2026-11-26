package com.streamlinemyjob.inspect.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One problem in a seed file (designs/stage-1-foundation.md 4.10): a stable code, the JSON path
 * of the problem (for example `lists[2].values[5].id`; empty for the whole file) and a detail.
 */
data class SeedError(val code: String, val path: String, val detail: String) {
    override fun toString(): String = buildString {
        append(code)
        if (path.isNotEmpty()) append(' ').append(path)
        if (detail.isNotEmpty()) append(" (").append(detail).append(')')
    }
}

/** A list's role (section 2). Roles are the only fixed thing; list names and contents are data. */
enum class Role { AREA, DAMAGE, ACTION }

/** A value as loaded from a seed: its id and its cleaned name (4.7). */
data class SeedValue(val id: String, val name: String)

/** A list as loaded from a seed: values in file order (the starting order). */
data class SeedList(val id: String, val role: Role, val name: String, val values: List<SeedValue>)

/** A valid seed: lists in file order. */
data class SeedData(val lists: List<SeedList>)

/**
 * The seed format, version 1: parser and validator (4.10). Every error is collected, not just the
 * first, each with its code and JSON path. Ids are opaque: nothing here reads meaning from one.
 */
object Seed {
    const val FORMAT = "smj-seed"
    const val VERSION = "1"

    private val TOP_FIELDS = listOf("format", "version", "lists")
    private val LIST_FIELDS = listOf("id", "role", "name", "values")
    private val VALUE_FIELDS = listOf("id", "name")

    sealed interface Parsed
    data class Valid(val seed: SeedData) : Parsed
    data class Invalid(val errors: List<SeedError>) : Parsed

    /** Every error in [text]; empty when the seed is valid. */
    fun validate(text: String): List<SeedError> = when (val p = parse(text)) {
        is Valid -> emptyList()
        is Invalid -> p.errors
    }

    /** Parses and validates [text]. A [Valid] result holds cleaned names (4.7) in file order. */
    fun parse(text: String): Parsed {
        val root: JsonElement = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return Invalid(listOf(SeedError(Codes.SEED_JSON, "", e.message?.lineSequence()?.firstOrNull()?.trim() ?: e.javaClass.simpleName)))
        }
        val v = Validation()
        val data = v.file(root as? JsonObject ?: JsonObject(emptyMap()))
        return if (v.errors.isEmpty() && data != null) Valid(data) else Invalid(v.errors)
    }

    private class Validation {
        val errors = mutableListOf<SeedError>()
        private val idFirstSeen = HashMap<String, String>()
        private val listNameFirstSeen = HashMap<String, String>()

        private fun error(code: String, path: String, detail: String) {
            errors += SeedError(code, path, detail)
        }

        /** Unknown fields, then missing ones, as `SEED_FIELD <path> (unknown|missing)`. */
        private fun fields(obj: JsonObject, path: String, expected: List<String>, reportMissing: Collection<String> = expected) {
            obj.keys.filter { it !in expected }.forEach { error(Codes.SEED_FIELD, join(path, it), "unknown") }
            reportMissing.filter { it !in obj }.forEach { error(Codes.SEED_FIELD, join(path, it), "missing") }
        }

        private fun join(path: String, field: String) = if (path.isEmpty()) field else "$path.$field"

        /** The string at [field], or null (with `SEED_FIELD … (not a string)` if it is there but is not one). */
        private fun string(obj: JsonObject, path: String, field: String): String? {
            val e = obj[field] ?: return null
            if (e is JsonPrimitive && e.isString) return e.content
            error(Codes.SEED_FIELD, join(path, field), "not a string: ${describe(e)}")
            return null
        }

        private fun array(obj: JsonObject, path: String, field: String): JsonArray? {
            val e = obj[field] ?: return null
            if (e is JsonArray) return e
            error(Codes.SEED_FIELD, join(path, field), "not an array: ${describe(e)}")
            return null
        }

        fun file(obj: JsonObject): SeedData? {
            val format = obj["format"]
            if (!(format is JsonPrimitive && format.isString && format.content == FORMAT)) {
                error(Codes.SEED_FORMAT, "format", "expected \"$FORMAT\", found ${describe(format)}")
            }
            val version = obj["version"]
            if (!(version is JsonPrimitive && !version.isString && version.content == VERSION)) {
                error(Codes.SEED_FORMAT, "version", "expected $VERSION, found ${describe(version)}")
            }
            // format and version are reported as SEED_FORMAT above, so only lists can be SEED_FIELD missing.
            fields(obj, "", TOP_FIELDS, reportMissing = listOf("lists"))
            val listsJson = array(obj, "", "lists") ?: return null
            val lists = listsJson.mapIndexed { i, e -> list(e, "lists[$i]") }
            counts(listsJson, lists)
            return if (lists.all { it != null }) SeedData(lists.filterNotNull()) else null
        }

        private fun id(obj: JsonObject, path: String): String? {
            val id = string(obj, path, "id") ?: return null
            val idPath = "$path.id"
            if (!Rules.SEED_ID.matches(id)) error(Codes.SEED_ID_BAD, idPath, describe(JsonPrimitive(id)))
            // Lists and values share one id space (4.10). The error is on the later use and names the first.
            val earlier = idFirstSeen.putIfAbsent(id, idPath)
            if (earlier != null) error(Codes.SEED_ID_DUP, idPath, "$id, also at $earlier")
            return id
        }

        /** 4.7's rules on a list or value name, as `SEED_NAME_*`; the cleaned name, or null if it is not valid. */
        private fun name(obj: JsonObject, path: String): Text.Name? {
            val raw = string(obj, path, "name") ?: return null
            val name = Text.checkName(raw)
            name.problems.forEach { p ->
                val detail = when (p.code) {
                    Codes.NAME_TOO_LONG -> "${p.detail} code points, at most ${Rules.NAME_MAX}"
                    else -> p.detail
                }
                error("SEED_${p.code}", "$path.name", detail)
            }
            return if (name.ok) name else null
        }

        private fun list(e: JsonElement, path: String): SeedList? {
            val obj = e as? JsonObject ?: run {
                error(Codes.SEED_FIELD, path, "not an object: ${describe(e)}")
                return null
            }
            fields(obj, path, LIST_FIELDS)
            val id = id(obj, path)
            val roleText = string(obj, path, "role")
            val role = roleText?.let { r -> Role.entries.firstOrNull { it.name == r } }
            if (roleText != null && role == null) error(Codes.SEED_ROLE, "$path.role", "${describe(JsonPrimitive(roleText))}, expected AREA, DAMAGE or ACTION")
            val name = name(obj, path)
            if (name != null) {
                val namePath = "$path.name"
                val earlier = listNameFirstSeen.putIfAbsent(name.key, namePath)
                if (earlier != null) error(Codes.SEED_LIST_NAME_DUP, namePath, "${name.cleaned}, same name as $earlier")
            }
            val valuesJson = array(obj, path, "values")
            val values = valuesJson?.let { arr ->
                val valueNameFirstSeen = HashMap<String, String>()
                arr.mapIndexed { j, v ->
                    val vPath = "$path.values[$j]"
                    value(v, vPath)?.also { (value, key) ->
                        val earlier = valueNameFirstSeen.putIfAbsent(key, "$vPath.name")
                        if (earlier != null) error(Codes.SEED_NAME_DUP, "$vPath.name", "${value.name}, same name as $earlier")
                    }?.first
                }
            }
            if (id == null || role == null || name == null || values == null || values.any { it == null }) return null
            return SeedList(id, role, name.cleaned, values.filterNotNull())
        }

        /** A value and its name key, or null (with its errors reported) if it is not valid. */
        private fun value(e: JsonElement, path: String): Pair<SeedValue, String>? {
            val obj = e as? JsonObject ?: run {
                error(Codes.SEED_FIELD, path, "not an object: ${describe(e)}")
                return null
            }
            fields(obj, path, VALUE_FIELDS)
            val id = id(obj, path)
            val name = name(obj, path)
            if (id == null || name == null) return null
            return SeedValue(id, name.cleaned) to name.key
        }

        /** `SEED_ROLE_COUNT` and `SEED_EMPTY_REQUIRED`, over the lists whose role could be read. */
        private fun counts(listsJson: JsonArray, lists: List<SeedList?>) {
            val roles = listsJson.map { e ->
                val r = ((e as? JsonObject)?.get("role") as? JsonPrimitive)?.takeIf { it.isString }?.content
                Role.entries.firstOrNull { it.name == r }
            }
            fun count(role: Role) = roles.count { it == role }
            for (role in listOf(Role.DAMAGE, Role.ACTION)) {
                when (val n = count(role)) {
                    1 -> {}
                    0 -> error(Codes.SEED_ROLE_COUNT, "", "no ${role.name} list")
                    else -> error(Codes.SEED_ROLE_COUNT, "", "$n ${role.name} lists, expected exactly one")
                }
            }
            if (count(Role.AREA) == 0) error(Codes.SEED_ROLE_COUNT, "", "no AREA list")

            fun valueCount(i: Int): Int? = ((listsJson[i] as? JsonObject)?.get("values") as? JsonArray)?.size
            val damage = roles.indices.filter { roles[it] == Role.DAMAGE }
            if (damage.size == 1 && valueCount(damage[0]) == 0) {
                error(Codes.SEED_EMPTY_REQUIRED, "lists[${damage[0]}].values", "the damage list has no values")
            }
            val areas = roles.indices.filter { roles[it] == Role.AREA }
            if (areas.isNotEmpty() && areas.all { valueCount(it) == 0 }) {
                error(Codes.SEED_EMPTY_REQUIRED, "", "no area list has any value")
            }
            // The action list may be empty (C3); an area list may be empty if another has values.
            check(lists.size == listsJson.size)
        }
    }

    private fun describe(e: JsonElement?): String = when (e) {
        null -> "nothing"
        else -> e.toString()
    }
}
