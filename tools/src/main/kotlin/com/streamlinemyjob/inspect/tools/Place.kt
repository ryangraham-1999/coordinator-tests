package com.streamlinemyjob.inspect.tools

/**
 * Where the tools run (designs/stage-1-foundation.md 2 "place", 4.5): `cloud` if the
 * environment variable `SMJ_PLACE` equals `cloud`, else `pc`. Nothing else decides it.
 */
enum class Place(val label: String) {
    CLOUD("cloud"),
    PC("pc");

    companion object {
        fun from(smjPlace: String?): Place = if (smjPlace == "cloud") CLOUD else PC

        fun fromEnvironment(): Place = from(System.getenv("SMJ_PLACE"))
    }
}
