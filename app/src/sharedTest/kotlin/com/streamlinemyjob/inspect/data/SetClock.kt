package com.streamlinemyjob.inspect.data

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * A clock whose instant is whatever was last [set] (designs/stage-1-foundation.md 4.15). It does not
 * tick: every read gives the same instant until it is set again. Europe/London unless told otherwise.
 * Until the first [set] it gives the time it was made at, to the millisecond.
 */
class SetClock(
    private var now: Instant = Instant.ofEpochMilli(System.currentTimeMillis()),
    private val zone: ZoneId = ZoneId.of("Europe/London"),
) : Clock() {
    fun set(epochMillis: Long) {
        now = Instant.ofEpochMilli(epochMillis)
    }

    override fun instant(): Instant = now
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = SetClock(now, zone)
}
