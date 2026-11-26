package com.streamlinemyjob.inspect.core

/** A region's id (4.20). Opaque, like seed ids: nothing reads meaning from it. */
typealias RegionId = String

/**
 * Resolving a tap (designs/stage-1-foundation.md 4.21). The one function the app and every test call:
 * no hidden bitmap, no hit boxes, no tolerance and no snapping to the nearest region (C18).
 */
object Hit {
    /**
     * The region under the point ([x], [y] in drawing units): `null` outside the frame (0..width,
     * 0..height, edges included), else the first region in file order that [contains] the point,
     * `null` if none does.
     */
    fun resolve(drawing: Drawing, x: Double, y: Double): RegionId? = resolveIn(drawing.width, drawing.height, drawing.regions, x, y)

    /** A dot (fractions of the frame, 0..1) resolved as `resolve(dotX * width, dotY * height)`. */
    fun resolveDot(drawing: Drawing, dotX: Double, dotY: Double): RegionId? =
        resolve(drawing, dotX * drawing.width, dotY * drawing.height)

    /** [resolve] over a frame and a region list that need not be a validated [Drawing] yet (the validator's anchor checks). */
    internal fun resolveIn(width: Int, height: Int, regions: List<Region>, x: Double, y: Double): RegionId? {
        if (x.isNaN() || y.isNaN()) return null
        if (x < 0.0 || x > width || y < 0.0 || y > height) return null
        return regions.firstOrNull { contains(it.points, x, y) }?.id
    }

    /**
     * The even-odd rule (a ray from the point to the right crosses the polygon's edges an odd number
     * of times), plus: a point exactly on an edge (cross product exactly 0.0 and inside the edge's
     * bounding box) counts as inside. No rounding and no tolerance.
     */
    fun contains(points: List<Pt>, x: Double, y: Double): Boolean {
        var inside = false
        val n = points.size
        for (i in 0 until n) {
            val a = points[i]
            val b = points[(i + 1) % n]
            val ax = a.x.toDouble()
            val ay = a.y.toDouble()
            val bx = b.x.toDouble()
            val by = b.y.toDouble()
            // Positive when the point is on one side of the edge, negative on the other, 0.0 on its line.
            val cross = (x - ax) * (by - ay) - (y - ay) * (bx - ax)
            if (cross == 0.0 && x >= minOf(ax, bx) && x <= maxOf(ax, bx) && y >= minOf(ay, by) && y <= maxOf(ay, by)) return true
            if ((ay > y) != (by > y)) {
                // The edge crosses the point's horizontal line; count it when that crossing is to the right of the point.
                val crossingIsRight = if (by > ay) cross < 0.0 else cross > 0.0
                if (crossingIsRight) inside = !inside
            }
        }
        return inside
    }
}
