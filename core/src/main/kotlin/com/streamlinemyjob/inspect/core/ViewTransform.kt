package com.streamlinemyjob.inspect.core

/**
 * How a drawing is seen on the screen once the owner has zoomed (designs/stage-1-foundation.md 4.28, C31):
 * the picture is [zoom] times its normal size and moved by ([offsetX], [offsetY]) pixels. A point of the
 * drawing at `(ux, uy)` units is on the canvas at `ux * scale * zoom + offsetX`, where `scale` is the
 * canvas's width in pixels divided by the drawing's width in units (4.20). The hit test is unchanged
 * (4.21): the screen turns a tap into drawing units with [toUnits] and calls `Hit.resolve`.
 */
data class ViewTransform(val zoom: Double = 1.0, val offsetX: Double = 0.0, val offsetY: Double = 0.0) {
    /** Drawing units under the canvas point ([px], [py]). */
    fun toUnits(px: Double, py: Double, scale: Double): Pair<Double, Double> =
        Pair((px - offsetX) / (scale * zoom), (py - offsetY) / (scale * zoom))

    /** The canvas point where the drawing point ([ux], [uy]) is. */
    fun toPixels(ux: Double, uy: Double, scale: Double): Pair<Double, Double> =
        Pair(ux * scale * zoom + offsetX, uy * scale * zoom + offsetY)

    /** Multiplies the zoom by [factor], keeping the canvas point ([focusX], [focusY]) over the same part of the drawing. */
    fun zoomedBy(factor: Double, focusX: Double, focusY: Double, canvasWidth: Double, canvasHeight: Double): ViewTransform {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val ratio = newZoom / zoom
        return ViewTransform(newZoom, focusX - (focusX - offsetX) * ratio, focusY - (focusY - offsetY) * ratio)
            .clamped(canvasWidth, canvasHeight)
    }

    /** Moves the picture by ([dx], [dy]) pixels. */
    fun pannedBy(dx: Double, dy: Double, canvasWidth: Double, canvasHeight: Double): ViewTransform =
        copy(offsetX = offsetX + dx, offsetY = offsetY + dy).clamped(canvasWidth, canvasHeight)

    /** Keeps the picture covering the whole canvas: no empty margin is ever shown. */
    fun clamped(canvasWidth: Double, canvasHeight: Double): ViewTransform = copy(
        offsetX = offsetX.coerceIn(canvasWidth - canvasWidth * zoom, 0.0),
        offsetY = offsetY.coerceIn(canvasHeight - canvasHeight * zoom, 0.0),
    )

    companion object {
        const val MIN_ZOOM = 1.0
        const val MAX_ZOOM = 6.0

        /** How much bigger the touch-and-hold magnifier shows the drawing than the screen does. */
        const val MAGNIFIER_FACTOR = 3.0
    }
}
