package com.streamlinemyjob.inspect.testdata

import com.streamlinemyjob.inspect.core.Drawing
import com.streamlinemyjob.inspect.core.Hit
import com.streamlinemyjob.inspect.core.RegionId
import java.util.Random

/** A point in drawing units. */
data class DrawingPoint(val x: Double, val y: Double)

/**
 * A point that resolves to a given region (designs/stage-1-foundation.md 4.26): drawn uniformly in the
 * region's bounding box until [Hit.resolve] gives the region. It uses [Hit.resolve] on purpose; the
 * independent check of `Hit` is the worked-example table and the properties in S1.26's tests.
 */
object PointSampler {
    /** After this many misses in a row the region is too small to sample, which is a drawing bug. */
    const val MAX_MISSES = 10_000

    fun inside(random: Random, drawing: Drawing, regionId: RegionId): DrawingPoint {
        val region = drawing.regions.firstOrNull { it.id == regionId } ?: error("no region $regionId in drawing ${drawing.id}")
        val minX = region.points.minOf { it.x }.toDouble()
        val maxX = region.points.maxOf { it.x }.toDouble()
        val minY = region.points.minOf { it.y }.toDouble()
        val maxY = region.points.maxOf { it.y }.toDouble()
        repeat(MAX_MISSES) {
            val x = minX + random.nextDouble() * (maxX - minX)
            val y = minY + random.nextDouble() * (maxY - minY)
            if (Hit.resolve(drawing, x, y) == regionId) return DrawingPoint(x, y)
        }
        error("no point of $regionId found in $MAX_MISSES tries: the region is too small (DRAWING_SMALL should have caught it)")
    }
}
