package com.streamlinemyjob.inspect.core

import java.util.Locale

/**
 * Reg rules (designs/stage-1-foundation.md 4.8). There is no check against GB plate patterns:
 * `ABZ 1234`, `A 1` and any other shape of 2 to 10 key characters are accepted.
 */
object Reg {
    /** The result of checking what was typed: [Ok] with the reg display and reg key, or [Refused]. */
    sealed interface Result
    data class Ok(val display: String, val key: String) : Result

    /** [existingVehicleId] is set for `REG_TAKEN`: the vehicle that already has this key (the screen offers to open it). */
    data class Refused(val problem: Problem, val existingVehicleId: String? = null) : Result

    private val SPACE_RUN = Regex(" +")

    private fun accepted(c: Int): Boolean =
        c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c == ' '.code || c == '-'.code

    /** The first character not allowed in a reg (4.8: letters A-Z a-z, digits, spaces, hyphens), or null. */
    private fun firstBadChar(typed: String): String? =
        typed.codePoints().toArray().firstOrNull { !accepted(it) }?.let { String(Character.toChars(it)) }

    /** Reg display: uppercase, hyphens kept, runs of spaces collapsed to one, trimmed. */
    fun display(typed: String): String = SPACE_RUN.replace(typed.uppercase(Locale.ROOT), " ").trim(' ')

    /** Reg key: uppercase with every space and hyphen removed. */
    fun key(typed: String): String = typed.uppercase(Locale.ROOT).filter { it != ' ' && it != '-' }

    /** Checks what was typed: `REG_BAD_CHAR <char>`, `REG_TOO_LONG`, `REG_TOO_SHORT`. */
    fun check(typed: String): Result {
        firstBadChar(typed)?.let { return Refused(Problem(Codes.REG_BAD_CHAR, it)) }
        if (Text.codePoints(typed) > Rules.REG_TYPED_MAX) return Refused(Problem(Codes.REG_TOO_LONG, "typed ${Text.codePoints(typed)}"))
        val key = key(typed)
        if (key.length < Rules.REG_KEY_MIN) return Refused(Problem(Codes.REG_TOO_SHORT, "key ${key.length}"))
        if (key.length > Rules.REG_KEY_MAX) return Refused(Problem(Codes.REG_TOO_LONG, "key ${key.length}"))
        return Ok(display(typed), key)
    }

    /**
     * Checks a new or renamed reg against the vehicles that exist, given as reg key to vehicle id:
     * as [check], plus `REG_TAKEN` with the existing vehicle's id when the key is already used.
     */
    fun checkNew(typed: String, existingByKey: Map<String, String>): Result {
        val result = check(typed)
        if (result !is Ok) return result
        val existing = existingByKey[result.key] ?: return result
        return Refused(Problem(Codes.REG_TAKEN, result.key), existingVehicleId = existing)
    }

    /**
     * The key a search query matches with (4.8): cleaned the same way into a key. An empty query
     * gives "" (matches every vehicle). A query with a bad character gives null: it matches
     * nothing and shows no error.
     */
    fun searchKey(query: String): String? = if (firstBadChar(query) != null) null else key(query)

    /** Whether a vehicle with [regKey] is found by [query]: its reg key contains the query's key. */
    fun matches(regKey: String, query: String): Boolean = searchKey(query)?.let { regKey.contains(it) } ?: false
}
