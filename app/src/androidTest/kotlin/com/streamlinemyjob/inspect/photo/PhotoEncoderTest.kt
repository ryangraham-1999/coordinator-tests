package com.streamlinemyjob.inspect.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamlinemyjob.inspect.core.Photos
import com.streamlinemyjob.inspect.testdata.ImageGenerator
import com.streamlinemyjob.inspect.ui.ScreenTestSupport
import java.time.LocalDate
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Designs/stage-2-camera.md 4.3 and 4.13 on a real Android runtime (section 6). Robolectric's Bitmap does not encode. */
@RunWith(AndroidJUnit4::class)
class PhotoEncoderTest {
    private val stamp = PhotoEncoder.StampInfo("AB12 CDE", LocalDate.of(2026, 11, 27), "Panels", "DSF door")

    private fun bitmap(img: ImageGenerator.Image): Bitmap = Bitmap.createBitmap(img.pixels, img.width, img.height, Bitmap.Config.ARGB_8888)

    private fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("JPEG must decode")

    private fun brightness(c: Int): Int = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3

    private fun rows(b: Bitmap, from: Int, to: Int): IntArray {
        val out = IntArray(b.width * (to - from))
        b.getPixels(out, 0, b.width, 0, from, b.width, to - from)
        return out
    }

    @Test
    fun everyKindAtEverySizeFitsTheCapAndFlagsReducedByTheRule() {
        val random = ScreenTestSupport.random("PhotoEncoderTest.everyKind").asKotlinRandom()
        for ((w, h) in ImageGenerator.SIZES) for (kind in ImageGenerator.KINDS) {
            val label = "$kind ${w}x$h"
            val src = bitmap(ImageGenerator.byKind(kind, w, h, random))
            val r = PhotoEncoder.encode(src, 0, stamp)
            src.recycle()
            assertTrue("$label must encode", r is PhotoEncoder.Encoded)
            r as PhotoEncoder.Encoded
            assertTrue("$label bytes ${r.bytes.size}", r.bytes.size <= 1_000_000)
            assertTrue("$label long side", maxOf(r.width, r.height) <= 2000)
            val img = decode(r.bytes)
            assertEquals(label, r.width, img.width)
            assertEquals(label, r.height, img.height)
            img.recycle()
            // Step 2 (never scale up) and step 5 (reduced exactly when quality or size is below the standard).
            val target = minOf(2000, maxOf(w, h))
            assertEquals(label, r.quality < 85 || maxOf(r.width, r.height) < target, r.reduced)
            assertTrue("$label quality", r.quality in 60..85 && r.quality % 5 == 0)
            assertTrue("$label never scaled up", maxOf(r.width, r.height) <= maxOf(w, h))
        }
    }

    @Test
    fun noiseAt4032x3024IsReduced() {
        val src = bitmap(ImageGenerator.noise(4032, 3024, ScreenTestSupport.random("PhotoEncoderTest.noise").asKotlinRandom()))
        val r = PhotoEncoder.encode(src, 0, stamp) as PhotoEncoder.Encoded
        assertTrue("noise must be reduced", r.reduced)
        assertTrue(r.bytes.size <= 1_000_000)
    }

    @Test
    fun theMarkerLandsWhere43Step1PutsIt() {
        val w = 1280
        val h = 960
        val expected = mapOf(0 to (0 to 0), 90 to (h - 1 to 0), 180 to (w - 1 to h - 1), 270 to (0 to w - 1))
        val found = HashMap<Int, Pair<Int, Int>>()
        for ((rot, at) in expected) {
            val src = bitmap(ImageGenerator.marker(w, h))
            // No stamp: at 180 degrees the marker lands in the strip's corner.
            val r = PhotoEncoder.encode(src, rot, null) as PhotoEncoder.Encoded
            val img = decode(r.bytes)
            assertEquals("rotation $rot", if (rot % 180 == 0) w to h else h to w, img.width to img.height)
            // One pixel is smeared by JPEG (q85 leaves about 18 levels at its peak), so find the centre of the red.
            var best = -1
            var weight = 0.0
            var sx = 0.0
            var sy = 0.0
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val c = img.getPixel(x, y)
                val red = ((c shr 16) and 0xFF) - (((c shr 8) and 0xFF) + (c and 0xFF)) / 2
                if (red > best) best = red
                if (red > 3) { weight += red; sx += red * x; sy += red * y }
            }
            assertTrue("rotation $rot red strength $best", best >= 8 && weight > 0)
            val bx = Math.round(sx / weight).toInt()
            val by = Math.round(sy / weight).toInt()
            found[rot] = bx to by
            assertTrue("rotation $rot found $bx,$by expected $at", Math.abs(bx - at.first) <= 8 && Math.abs(by - at.second) <= 8)
        }
        assertTrue("90 and 270 must differ", found.getValue(90) != found.getValue(270))
    }

    @Test
    fun theStampIsABottomStripAndNothingElseChanges() {
        val img = ImageGenerator.smooth(2048, 1536, ScreenTestSupport.random("PhotoEncoderTest.stamp").asKotlinRandom())
        val src = bitmap(img)
        val stamped = PhotoEncoder.encode(src, 0, stamp) as PhotoEncoder.Encoded
        val plain = PhotoEncoder.encode(src, 0, null) as PhotoEncoder.Encoded
        assertEquals(stamped.quality, plain.quality)
        assertEquals(2000 to 1500, stamped.width to stamped.height)
        val s = decode(stamped.bytes)
        val p = decode(plain.bytes)
        val band = Photos.stampBandHeight(1500)
        assertEquals(68, band)
        val top = s.height - band
        val sStrip = rows(s, top, s.height)
        val pStrip = rows(p, top, p.height)
        val darker = pStrip.map { brightness(it) }.average() - sStrip.map { brightness(it) }.average()
        assertTrue("strip darker by $darker", darker >= 40)
        assertTrue("strip holds white text pixels", sStrip.any { (it and 0xFF) >= 240 && ((it shr 8) and 0xFF) >= 240 && ((it shr 16) and 0xFF) >= 240 })
        val sAbove = rows(s, 0, top - 2)
        val pAbove = rows(p, 0, top - 2)
        var sum = 0L
        for (i in sAbove.indices) {
            sum += Math.abs(((sAbove[i] shr 16) and 0xFF) - ((pAbove[i] shr 16) and 0xFF)) +
                Math.abs(((sAbove[i] shr 8) and 0xFF) - ((pAbove[i] shr 8) and 0xFF)) +
                Math.abs((sAbove[i] and 0xFF) - (pAbove[i] and 0xFF))
        }
        val mad = sum / (3.0 * sAbove.size)
        assertTrue("rows above the strip differ by $mad", mad < 3)
    }

    @Test
    fun theStampIsDrawnAfterScalingSoItIsTheSameShareOfAnySize() {
        // 4032 x 3024 noise ends reduced and smaller, but the strip is still 4.5% of the short side of what is stored.
        val src = bitmap(ImageGenerator.smooth(4032, 3024, Random(1)))
        val r = PhotoEncoder.encode(src, 0, stamp) as PhotoEncoder.Encoded
        val img = decode(r.bytes)
        val band = Photos.stampBandHeight(minOf(r.width, r.height))
        val strip = rows(img, img.height - band, img.height).map { brightness(it) }.average()
        val above = rows(img, img.height - band - 2 * band, img.height - band - band).map { brightness(it) }.average()
        assertTrue("strip $strip vs above $above", above - strip >= 40)
        // And the row just above the strip is not darkened.
        val justAbove = rows(img, img.height - band - 4, img.height - band - 1).map { brightness(it) }.average()
        assertTrue("just above the strip $justAbove vs $above", Math.abs(justAbove - above) < 10)
    }

    @Test
    fun sizeAndTimeOfEachKindAt2048x1536AreLogged() {
        val random = ScreenTestSupport.random("PhotoEncoderTest.measure").asKotlinRandom()
        for (kind in ImageGenerator.KINDS) {
            val src = bitmap(ImageGenerator.byKind(kind, 2048, 1536, random))
            val t0 = System.nanoTime()
            val r = PhotoEncoder.encode(src, 0, stamp) as PhotoEncoder.Encoded
            val ms = (System.nanoTime() - t0) / 1_000_000
            Log.i("SMJ_PHOTO", "kind=$kind 2048x1536 bytes=${r.bytes.size} quality=${r.quality} ${r.width}x${r.height} reduced=${r.reduced} tries=${r.tries} ms=$ms")
            assertTrue(r.bytes.size <= 1_000_000)
        }
    }
}
