package com.streamlinemyjob.inspect.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import com.streamlinemyjob.inspect.core.Photos
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/**
 * Turns a shot (or a test or gallery image) into the stored JPEG (designs/stage-2-camera.md 4.3, 4.13):
 * rotate upright, then for each try scale, stamp the scaled copy and compress. The step-down rule itself
 * is [Photos.plan]; this class does the bitmap work for it. No EXIF is written (the JPEG is re-encoded
 * from pixels).
 */
object PhotoEncoder {
    /** What the stamp says (4.13); the names are as the finding has them when the photo is saved. */
    data class StampInfo(val regDisplay: String, val inspectionDate: LocalDate, val areaListName: String, val areaName: String)

    sealed interface Result

    /** The stored photo: [bytes] are the JPEG, [width] x [height] its size, [quality] and [reduced] by 4.3 step 5. */
    class Encoded(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val quality: Int,
        val reduced: Boolean,
        val tries: Int,
    ) : Result

    /** Nothing fitted (`PHOTO_TOO_BIG`, 4.3 step 4): nothing is saved. */
    class TooBig(val tries: Int) : Result {
        val code: String get() = Photos.PHOTO_TOO_BIG
    }

    /**
     * Encodes [source] turned clockwise by [rotationDegrees] (0, 90, 180 or 270) with [stamp] drawn on each
     * scaled copy. A null [stamp] draws none (only for tests that compare with the unstamped picture; the
     * app always stamps). [source] is left untouched.
     */
    fun encode(source: Bitmap, rotationDegrees: Int, stamp: StampInfo?): Result {
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "rotationDegrees must be 0, 90, 180 or 270" }
        val upright = rotate(source, rotationDegrees)
        try {
            var lastBytes = ByteArray(0)
            val plan = Photos.plan(upright.width, upright.height) { longSide, quality ->
                lastBytes = compress(upright, longSide, quality, stamp)
                lastBytes.size
            }
            return when (plan) {
                // plan() returns on the first fit, so the last try's bytes are the kept ones.
                is Photos.Kept -> {
                    val (w, h) = Photos.scaledSize(upright.width, upright.height, plan.longSide)
                    Encoded(lastBytes, w, h, plan.quality, plan.reduced, plan.tries)
                }
                is Photos.TooBig -> TooBig(plan.tries)
            }
        } finally {
            if (upright !== source) upright.recycle()
        }
    }

    /** 4.3 step 1: a pixel at (x, y) of a W x H image goes to (H-1-y, x) for 90, and so on (`Matrix.postRotate` is clockwise). */
    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, false)
    }

    private fun compress(upright: Bitmap, longSide: Int, quality: Int, stamp: StampInfo?): ByteArray {
        val (w, h) = Photos.scaledSize(upright.width, upright.height, longSide)
        // Always a fresh mutable copy: createScaledBitmap can hand back the source when the size is unchanged.
        val scaled = if (w == upright.width && h == upright.height) {
            upright.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            val s = Bitmap.createScaledBitmap(upright, w, h, true)
            if (s.isMutable) s else s.copy(Bitmap.Config.ARGB_8888, true).also { s.recycle() }
        }
        try {
            if (stamp != null) drawStamp(scaled, stamp)
            val out = ByteArrayOutputStream(256 * 1024)
            check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG compress failed" }
            return out.toByteArray()
        } finally {
            scaled.recycle()
        }
    }

    /** 4.13: a black 60% strip along the bottom, white text sized and fitted by [Photos.fitStamp]. */
    private fun drawStamp(bitmap: Bitmap, stamp: StampInfo) {
        val w = bitmap.width
        val h = bitmap.height
        val band = Photos.stampBandHeight(minOf(w, h))
        val padding = Photos.stampPadding(band)
        val canvas = Canvas(bitmap)
        val strip = Paint().apply { color = 0x99000000.toInt(); style = Paint.Style.FILL }
        val top = (h - band).coerceAtLeast(0)
        canvas.drawRect(0f, top.toFloat(), w.toFloat(), h.toFloat(), strip)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.SANS_SERIF
        }
        val fit = Photos.fitStamp(
            stamp.regDisplay, stamp.inspectionDate, stamp.areaListName, stamp.areaName,
            band, (w - 2 * padding).toFloat(),
        ) { text, size -> paint.textSize = size.toFloat(); paint.measureText(text) }
        paint.textSize = fit.fontSize.toFloat()
        val metrics = paint.fontMetrics
        val baseline = top + band / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(fit.text, padding.toFloat(), baseline, paint)
    }
}
