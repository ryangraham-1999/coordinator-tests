package com.streamlinemyjob.inspect.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Designs/stage-2-camera.md 4.3 worked examples 1-5 and the cap's edge, with a fake encoder (section 6). */
class PhotoPlanTest {
    private class Fake(val bytes: (Int, Int) -> Int) {
        val calls = mutableListOf<Pair<Int, Int>>()
        fun encode(l: Int, q: Int): Int { calls += l to q; return bytes(l, q) }
    }

    @Test
    fun example1TypicalShot() {
        // 2048 x 1536 turned 90 degrees is 1536 x 2048 upright.
        val f = Fake { _, _ -> 640_000 }
        val r = Photos.plan(1536, 2048, f::encode)
        assertEquals(Photos.Kept(2000, 85, reduced = false, tries = 1), r)
        assertEquals(listOf(2000 to 85), f.calls)
        assertEquals(1500 to 2000, Photos.scaledSize(1536, 2048, 2000))
    }

    @Test
    fun example2BusyShot() {
        val sizes = mapOf(85 to 1_180_000, 80 to 1_020_000, 75 to 905_000)
        val f = Fake { _, q -> sizes.getValue(q) }
        val r = Photos.plan(2048, 1536, f::encode)
        assertEquals(Photos.Kept(2000, 75, reduced = true, tries = 3), r)
        assertEquals(2000 to 1500, Photos.scaledSize(2048, 1536, 2000))
    }

    @Test
    fun example3WorstCaseShrinks() {
        val f = Fake { l, q ->
            when {
                l == 2000 -> 1_450_000
                l == 1700 -> 1_080_000
                l == 1445 && q == 60 -> 790_000
                else -> error("unexpected $l $q")
            }
        }
        val r = Photos.plan(4032, 3024, f::encode)
        assertEquals(Photos.Kept(1445, 60, reduced = true, tries = 8), r)
        assertEquals(1445 to 1084, Photos.scaledSize(4032, 3024, 1445))
        assertEquals(listOf(2000, 2000, 2000, 2000, 2000, 2000, 1700, 1445), f.calls.map { it.first })
    }

    @Test
    fun example4SmallSourceIsNeverScaledUp() {
        val f = Fake { _, _ -> 300_000 }
        val r = Photos.plan(1280, 960, f::encode)
        assertEquals(Photos.Kept(1280, 85, reduced = false, tries = 1), r)
    }

    @Test
    fun example5RefusedAfterElevenTries() {
        val f = Fake { _, _ -> 1_000_001 }
        val r = Photos.plan(4032, 3024, f::encode)
        assertEquals(Photos.TooBig(11), r)
        assertEquals(listOf(2000, 2000, 2000, 2000, 2000, 2000, 1700, 1445, 1228, 1044, 887), f.calls.map { it.first })
        assertEquals(List(6) { 85 - 5 * it } + List(5) { 60 }, f.calls.map { it.second })
    }

    @Test
    fun exactlyTheCapIsKeptOneByteOverIsNot() {
        val at = Photos.plan(2048, 1536) { _, _ -> 1_000_000 }
        assertTrue(at is Photos.Kept)
        assertEquals(1, (at as Photos.Kept).tries)
        val over = Photos.plan(2048, 1536) { _, _ -> 1_000_001 }
        assertEquals(Photos.TooBig(11), over)
    }

    @Test
    fun reducedWhenQualityOrSizeBelowTheStandard() {
        // Fits only once the long side is below the target at q60.
        val r = Photos.plan(2048, 1536) { l, q -> if (l <= 1700 && q == 60) 900_000 else 2_000_000 } as Photos.Kept
        assertTrue(r.reduced)
        // Fits at the first try: not reduced, also for a source smaller than the target.
        assertFalse((Photos.plan(640, 480) { _, _ -> 100 } as Photos.Kept).reduced)
    }

    @Test
    fun anyShapeFollowsTheSameRule() {
        for ((w, h) in listOf(64 to 48, 3000 to 3000, 4000 to 1000, 1000 to 4000, 2001 to 1, 1 to 2001)) {
            val l = Photos.targetLongSide(w, h)
            assertEquals(minOf(2000, maxOf(w, h)), l)
            val (sw, sh) = Photos.scaledSize(w, h, l)
            assertEquals(l, maxOf(sw, sh))
            assertEquals(w >= h, sw >= sh)
        }
    }
}
