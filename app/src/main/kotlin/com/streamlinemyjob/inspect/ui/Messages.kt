package com.streamlinemyjob.inspect.ui

import com.streamlinemyjob.inspect.core.Codes
import com.streamlinemyjob.inspect.data.RepoCodes

/**
 * The owner's text for a refused write (designs/stage-1-foundation.md 4.16). The code goes in the
 * test, the text goes to the owner. Codes these screens do not have a text for show the code itself,
 * so it can be reported.
 */
object Messages {
    const val REG = "Use letters, numbers, spaces and hyphens (2–10 letters and numbers)."
    const val REG_TAKEN = "That reg is already saved."
    const val MILEAGE = "Type the mileage as a whole number up to 999,999."
    const val DATE = "Type a date as DD/MM/YYYY, from 1990 up to today."

    const val NAME_EMPTY = "Type a name."
    const val NAME_TOO_LONG = "Names can be up to 40 characters."
    const val LAST_DAMAGE_VALUE = "Keep at least one damage type."
    const val LAST_AREA_VALUE = "Keep at least one area to pick."

    fun nameTaken(name: String) = "This list already has \"$name\"."
    fun nameTakenByRemoved(name: String) = "A removed value is called \"$name\". Restore it instead, or pick another name."

    /** [name] is the name the owner typed, for the texts that quote it. */
    fun forCode(code: String, detail: String = "", name: String = ""): String = when {
        code == Codes.NAME_EMPTY -> NAME_EMPTY
        code == Codes.NAME_TOO_LONG -> NAME_TOO_LONG
        code == Codes.NAME_TAKEN -> nameTaken(name)
        code == RepoCodes.NAME_TAKEN_BY_REMOVED -> nameTakenByRemoved(name)
        code == RepoCodes.LAST_DAMAGE_VALUE -> LAST_DAMAGE_VALUE
        code == RepoCodes.LAST_AREA_VALUE -> LAST_AREA_VALUE
        code == Codes.REG_TAKEN -> REG_TAKEN
        code.startsWith("REG_") -> REG
        code.startsWith("MILEAGE_") -> MILEAGE
        code.startsWith("DATE_") -> DATE
        detail.isEmpty() -> code
        else -> "$code $detail"
    }
}
