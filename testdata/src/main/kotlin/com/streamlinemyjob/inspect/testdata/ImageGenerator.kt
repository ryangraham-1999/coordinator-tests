package com.streamlinemyjob.inspect.testdata

import kotlin.random.Random

/**
 * Generated test images (designs/stage-2-camera.md 4.10): plain ARGB pixels, no Android, so the same
 * generator feeds JVM tests and the phone tests. Every image is opaque. Never a real photo (RULES 6).
 */
object ImageGenerator {
    /** An image: [pixels] are row by row, `0xAARRGGBB`, `width * height` of them. */
    class Image(val width: Int, val height: Int, val pixels: IntArray) {
        init { require(pixels.size == width * height) }
        fun pixel(x: Int, y: Int): Int = pixels[y * width + x]
    }

    /** The sizes the phone tests use (4.10). */
    val SIZES: List<Pair<Int, Int>> = listOf(4032 to 3024, 2048 to 1536, 1280 to 960, 64 to 48)

    val KINDS: List<String> = listOf("smooth", "busy", "noise", "marker")

    fun byKind(kind: String, w: Int, h: Int, random: Random): Image = when (kind) {
        "smooth" -> smooth(w, h, random)
        "busy" -> busy(w, h, random)
        "noise" -> noise(w, h, random)
        "marker" -> marker(w, h)
        else -> error("unknown kind $kind")
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Gradients: three smooth colour ramps with random directions and offsets. Compresses very well. */
    fun smooth(w: Int, h: Int, random: Random): Image {
        val a = DoubleArray(9) { random.nextDouble(-1.0, 1.0) }
        val base = IntArray(3) { random.nextInt(130, 211) }
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val fy = y.toDouble() / h
            for (x in 0 until w) {
                val fx = x.toDouble() / w
                px[y * w + x] = rgb(
                    base[0] + (50 * (a[0] * fx + a[1] * fy + a[2] * fx * fy)).toInt(),
                    base[1] + (50 * (a[3] * fx + a[4] * fy + a[5] * fx * fy)).toInt(),
                    base[2] + (50 * (a[6] * fx + a[7] * fy + a[8] * fx * fy)).toInt(),
                )
            }
        }
        return Image(w, h, px)
    }

    /** Many small high-contrast rectangles and lines over a gradient. Roughly like a busy real scene. */
    fun busy(w: Int, h: Int, random: Random): Image {
        val img = smooth(w, h, random)
        val px = img.pixels
        val shapes = (w.toLong() * h / 400).toInt().coerceAtLeast(40)
        repeat(shapes) {
            val colour = rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            if (random.nextBoolean()) {
                val rw = random.nextInt(2, (w / 40).coerceAtLeast(4))
                val rh = random.nextInt(2, (h / 40).coerceAtLeast(4))
                val x0 = random.nextInt(0, w)
                val y0 = random.nextInt(0, h)
                for (y in y0 until minOf(h, y0 + rh)) for (x in x0 until minOf(w, x0 + rw)) px[y * w + x] = colour
            } else {
                var x = random.nextInt(0, w)
                var y = random.nextInt(0, h)
                val dx = random.nextInt(-2, 3)
                val dy = random.nextInt(-2, 3)
                repeat(random.nextInt(10, (maxOf(w, h) / 10).coerceAtLeast(12))) {
                    if (x in 0 until w && y in 0 until h) px[y * w + x] = colour
                    x += dx
                    y += dy
                }
            }
        }
        return img
    }

    /** Per-pixel random colour: the hardest case for JPEG. Never a repeating pattern or a tile. */
    fun noise(w: Int, h: Int, random: Random): Image {
        val px = IntArray(w * h)
        for (i in px.indices) px[i] = 0xFF000000.toInt() or (random.nextInt() and 0xFFFFFF)
        return Image(w, h, px)
    }

    /** White with one red pixel at (0, 0), for rotation tests; the top-left is clear of the stamp strip at the bottom. */
    fun marker(w: Int, h: Int): Image {
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        px[0] = 0xFFFF0000.toInt()
        return Image(w, h, px)
    }
}
