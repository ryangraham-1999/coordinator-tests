package com.streamlinemyjob.inspect.core

import java.time.LocalDate

/** Shared limits (designs/stage-1-foundation.md 4.7-4.10). Every caller reads them from here. */
object Rules {
    /** A list or value name: 1 to 40 Unicode code points of the cleaned name (4.7). */
    const val NAME_MAX = 40

    /** A finding's note: 0 to 500 code points; 0 means no note (4.7). */
    const val NOTE_MAX = 500

    /** What is typed for a reg: at most 20 characters (4.8). */
    const val REG_TYPED_MAX = 20

    /** The reg key: 2 to 10 characters (4.8). */
    const val REG_KEY_MIN = 2
    const val REG_KEY_MAX = 10

    /** Mileage: 1 to 6 digits, 0 to 999,999 (4.9). */
    const val MILEAGE_DIGITS_MAX = 6
    const val MILEAGE_MAX = 999_999

    /** The earliest inspection date (4.9); the latest is today on the phone's clock. */
    val DATE_MIN: LocalDate = LocalDate.of(1990, 1, 1)

    /** A seed id (4.10). */
    val SEED_ID = Regex("[a-z0-9][a-z0-9-]{0,63}")
}

/** The stable error codes of the core rules (4.7-4.10). */
object Codes {
    const val NAME_EMPTY = "NAME_EMPTY"
    const val NAME_TOO_LONG = "NAME_TOO_LONG"
    const val NAME_BAD_CHAR = "NAME_BAD_CHAR"
    const val NAME_TAKEN = "NAME_TAKEN"
    const val NOTE_TOO_LONG = "NOTE_TOO_LONG"

    const val REG_BAD_CHAR = "REG_BAD_CHAR"
    const val REG_TOO_LONG = "REG_TOO_LONG"
    const val REG_TOO_SHORT = "REG_TOO_SHORT"
    const val REG_TAKEN = "REG_TAKEN"

    const val MILEAGE_EMPTY = "MILEAGE_EMPTY"
    const val MILEAGE_BAD = "MILEAGE_BAD"
    const val MILEAGE_TOO_BIG = "MILEAGE_TOO_BIG"

    const val DATE_BAD = "DATE_BAD"
    const val DATE_OUT_OF_RANGE = "DATE_OUT_OF_RANGE"

    const val SEED_JSON = "SEED_JSON"
    const val SEED_FORMAT = "SEED_FORMAT"
    const val SEED_FIELD = "SEED_FIELD"
    const val SEED_ROLE = "SEED_ROLE"
    const val SEED_ROLE_COUNT = "SEED_ROLE_COUNT"
    const val SEED_ID_BAD = "SEED_ID_BAD"
    const val SEED_ID_DUP = "SEED_ID_DUP"
    const val SEED_NAME_EMPTY = "SEED_NAME_EMPTY"
    const val SEED_NAME_TOO_LONG = "SEED_NAME_TOO_LONG"
    const val SEED_NAME_BAD_CHAR = "SEED_NAME_BAD_CHAR"
    const val SEED_NAME_DUP = "SEED_NAME_DUP"
    const val SEED_LIST_NAME_DUP = "SEED_LIST_NAME_DUP"
    const val SEED_EMPTY_REQUIRED = "SEED_EMPTY_REQUIRED"

    /** Every `SEED_*` code the validator can report, in 4.10's table order. */
    val SEED_ALL: List<String> = listOf(
        SEED_JSON, SEED_FORMAT, SEED_FIELD, SEED_ROLE, SEED_ROLE_COUNT, SEED_ID_BAD, SEED_ID_DUP,
        SEED_NAME_EMPTY, SEED_NAME_TOO_LONG, SEED_NAME_BAD_CHAR, SEED_NAME_DUP, SEED_LIST_NAME_DUP, SEED_EMPTY_REQUIRED,
    )

    const val DRAWING_JSON = "DRAWING_JSON"
    const val DRAWING_FORMAT = "DRAWING_FORMAT"
    const val DRAWING_FIELD = "DRAWING_FIELD"
    const val DRAWING_SIZE = "DRAWING_SIZE"
    const val DRAWING_ID_BAD = "DRAWING_ID_BAD"
    const val DRAWING_ID_DUP = "DRAWING_ID_DUP"
    const val DRAWING_NO_REGIONS = "DRAWING_NO_REGIONS"
    const val DRAWING_DECOR = "DRAWING_DECOR"
    const val DRAWING_POINTS = "DRAWING_POINTS"
    const val DRAWING_SELF_CROSS = "DRAWING_SELF_CROSS"
    const val DRAWING_ANCHOR = "DRAWING_ANCHOR"
    const val DRAWING_GAP = "DRAWING_GAP"
    const val DRAWING_SMALL = "DRAWING_SMALL"
    const val DRAWING_TEXT_EMPTY = "DRAWING_TEXT_EMPTY"
    const val DRAWING_TEXT_TOO_LONG = "DRAWING_TEXT_TOO_LONG"
    const val DRAWING_TEXT_BAD_CHAR = "DRAWING_TEXT_BAD_CHAR"

    /** Every `DRAWING_*` code the validator can report, in 4.20's table order. */
    val DRAWING_ALL: List<String> = listOf(
        DRAWING_JSON, DRAWING_FORMAT, DRAWING_FIELD, DRAWING_SIZE, DRAWING_ID_BAD, DRAWING_ID_DUP, DRAWING_NO_REGIONS,
        DRAWING_DECOR, DRAWING_POINTS, DRAWING_SELF_CROSS, DRAWING_ANCHOR, DRAWING_GAP, DRAWING_SMALL,
        DRAWING_TEXT_EMPTY, DRAWING_TEXT_TOO_LONG, DRAWING_TEXT_BAD_CHAR,
    )

    const val LINKS_JSON = "LINKS_JSON"
    const val LINKS_FORMAT = "LINKS_FORMAT"
    const val LINKS_FIELD = "LINKS_FIELD"
    const val LINKS_DRAWING = "LINKS_DRAWING"
    const val LINKS_REGION_UNKNOWN = "LINKS_REGION_UNKNOWN"
    const val LINKS_REGION_DUP = "LINKS_REGION_DUP"
    const val LINKS_VALUE_UNKNOWN = "LINKS_VALUE_UNKNOWN"
    const val LINKS_VALUE_ROLE = "LINKS_VALUE_ROLE"

    /** Every `LINKS_*` code the validator can report, in 4.22's table order. */
    val LINKS_ALL: List<String> = listOf(
        LINKS_JSON, LINKS_FORMAT, LINKS_FIELD, LINKS_DRAWING, LINKS_REGION_UNKNOWN, LINKS_REGION_DUP, LINKS_VALUE_UNKNOWN, LINKS_VALUE_ROLE,
    )
}

/** One refused input: a code from [Codes] and a detail (may be empty). */
data class Problem(val code: String, val detail: String = "") {
    override fun toString(): String = if (detail.isEmpty()) code else "$code $detail"
}
