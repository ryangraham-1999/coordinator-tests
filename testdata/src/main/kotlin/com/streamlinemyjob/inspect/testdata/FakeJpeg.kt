package com.streamlinemyjob.inspect.testdata

import java.util.Random

/**
 * Fake photo bytes for the data tests (designs/stage-2-camera.md 4.10): `FF D8`, 1,000 to 50,000 random bytes, `FF D9`. They are
 * not a picture: the Robolectric tests never decode or encode one (section 9); they only need a file with a length and a JPEG start.
 */
object FakeJpeg {
    const val MIN_RANDOM_BYTES = 1_000
    const val MAX_RANDOM_BYTES = 50_000

    fun make(random: Random): ByteArray {
        val n = MIN_RANDOM_BYTES + random.nextInt(MAX_RANDOM_BYTES - MIN_RANDOM_BYTES + 1)
        val out = ByteArray(n + 4)
        out[0] = 0xFF.toByte()
        out[1] = 0xD8.toByte()
        val middle = ByteArray(n)
        random.nextBytes(middle)
        System.arraycopy(middle, 0, out, 2, n)
        out[n + 2] = 0xFF.toByte()
        out[n + 3] = 0xD9.toByte()
        return out
    }
}
