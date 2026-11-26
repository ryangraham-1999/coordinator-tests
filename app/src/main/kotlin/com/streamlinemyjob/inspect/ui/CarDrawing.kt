package com.streamlinemyjob.inspect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamlinemyjob.inspect.core.Drawing
import com.streamlinemyjob.inspect.core.Hit
import com.streamlinemyjob.inspect.core.ViewTransform
import com.streamlinemyjob.inspect.data.Dot
import kotlin.math.roundToInt

/**
 * What the canvas shows of the zoom, as text `zoom;offsetX;offsetY` (pixels), so a test can map a point of the
 * drawing to the canvas the way the screen does. Set on the node tagged `car-drawing`.
 */
val DrawingViewKey = SemanticsPropertyKey<String>("DrawingView")
var SemanticsPropertyReceiver.drawingView by DrawingViewKey

/** A dot drawn over the drawing as its own node (the Inspection and Vehicle screens, 4.28): [x] and [y] are fractions of the frame. */
class DrawingMarker(
    val tag: String,
    val x: Double,
    val y: Double,
    val label: String?,
    val faded: Boolean = false,
    val description: String? = null,
    val onClick: (() -> Unit)? = null,
)

/**
 * The car drawing (designs/stage-1-foundation.md 4.25, 4.28): the regions, `decor` lines and four fixed texts
 * of the drawing file, scaled to the available width (at most 480 dp), in theme colours. [selectedRegion] is
 * highlighted; [dot] is drawn last; [markers] sit over the drawing as nodes. A tap is reported in drawing
 * units through [onTap], converted with the inverse of the screen's scale, zoom and offset, so the caller
 * resolves it with the one hit test (4.21). Pinch and drag zoom the picture, and touch-and-hold shows a magnifier:
 * slide to the part and let go to tap there (C31 c). Test tags: `car-drawing` on the canvas,
 * `drawing-magnifier` while the magnifier shows.
 */
@Composable
fun CarDrawing(
    drawing: Drawing,
    modifier: Modifier = Modifier,
    selectedRegion: String? = null,
    dot: Dot? = null,
    markers: List<DrawingMarker> = emptyList(),
    onTap: ((unitsX: Double, unitsY: Double) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val fill = colors.surfaceVariant
    val outline = colors.outline
    val selectedFill = colors.primaryContainer
    val selectedStroke = colors.primary
    val dotColor = colors.error
    val ringColor = colors.surface
    val textColor = colors.onSurfaceVariant
    val background = colors.surface
    // The part under the magnifier's crosshair: a strong, clearly different fill from the plain parts (and from a picked part).
    val aimedFill = colors.primary.copy(alpha = 0.55f)
    val crossColor = colors.onSurface
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = textColor)
    val markerStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onError, fontSize = 11.sp)
    val paths = remember(drawing) { drawing.regions.map { r -> r.id to polygon(r.points.map { Offset(it.x.toFloat(), it.y.toFloat()) }, true) } }
    val decorPaths = remember(drawing) { drawing.decor.map { d -> polygon(d.points.map { Offset(it.x.toFloat(), it.y.toFloat()) }, d.closed) } }

    var view by remember { mutableStateOf(ViewTransform()) }
    var magnifierAt by remember { mutableStateOf<Offset?>(null) }

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val widthDp = if (maxWidth < 480.dp) maxWidth else 480.dp
        val widthPx = with(density) { widthDp.toPx() }.toDouble()
        val heightPx = widthPx * drawing.height / drawing.width
        val scale = widthPx / drawing.width
        // A drawing that is smaller than before (a rotation) must not leave the picture off the canvas.
        val current = view.clamped(widthPx, heightPx)

        // Reads the zoom at the moment of the tap: the gesture detectors below are not restarted when it changes.
        fun report(p: Offset) {
            val (ux, uy) = view.clamped(widthPx, heightPx).toUnits(p.x.toDouble(), p.y.toDouble(), scale)
            onTap?.invoke(ux, uy)
        }

        Box(
            Modifier.width(widthDp).aspectRatio(drawing.width.toFloat() / drawing.height).clipToBounds(),
        ) {
            val canvasModifier = Modifier
                .fillMaxWidth()
                .aspectRatio(drawing.width.toFloat() / drawing.height)
                .testTag("car-drawing")
                .semantics { drawingView = "${current.zoom};${current.offsetX};${current.offsetY}" }
                .pointerInput(widthPx) {
                    detectZoomAndDrag(canDrag = { view.zoom > ViewTransform.MIN_ZOOM }) { centroid, pan, zoom ->
                        view = view.zoomedBy(zoom.toDouble(), centroid.x.toDouble(), centroid.y.toDouble(), widthPx, heightPx)
                            .pannedBy(pan.x.toDouble(), pan.y.toDouble(), widthPx, heightPx)
                    }
                }
                .pointerInput(widthPx, onTap) {
                    detectTapGestures(onTap = { report(it) })
                }
                .pointerInput(widthPx, onTap) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { magnifierAt = it },
                        onDrag = { change, _ -> magnifierAt = change.position },
                        onDragEnd = {
                            magnifierAt?.let { report(it) }
                            magnifierAt = null
                        },
                        onDragCancel = { magnifierAt = null },
                    )
                }
            // The region under the magnifier's crosshair, shown highlighted inside the magnifier so the part being aimed at stands out.
            val aimed: Offset? = magnifierAt?.let { finger ->
                val (ux, uy) = current.toUnits(finger.x.toDouble(), finger.y.toDouble(), scale)
                Offset(ux.toFloat(), uy.toFloat())
            }
            val aimedRegion = aimed?.let { Hit.resolve(drawing, it.x.toDouble(), it.y.toDouble()) }

            fun DrawScope.scene(t: ViewTransform, highlight: String?, withMarkers: Boolean) {
                val k = (scale * t.zoom).toFloat()
                withTransform({
                    translate(t.offsetX.toFloat(), t.offsetY.toFloat())
                    scale(k, k, Offset.Zero)
                }) {
                    for ((id, path) in paths) {
                        val strong = id == selectedRegion || id == highlight
                        drawPath(path, if (id == highlight) aimedFill else if (strong) selectedFill else fill)
                        val stroke = (if (strong) 2.dp else 1.dp).toPx() / k
                        drawPath(path, if (strong) selectedStroke else outline, style = Stroke(width = stroke))
                    }
                }
                withTransform({
                    translate(t.offsetX.toFloat(), t.offsetY.toFloat())
                    scale(k, k, Offset.Zero)
                }) {
                    for (path in decorPaths) drawPath(path, outline, style = Stroke(width = 0.5.dp.toPx() / k))
                }
                for (text in drawing.texts) {
                    val (px, py) = t.toPixels(text.at.x.toDouble(), text.at.y.toDouble(), scale)
                    val laid = measurer.measure(text.text, labelStyle)
                    drawText(laid, topLeft = Offset(px.toFloat() - laid.size.width / 2f, py.toFloat() - laid.size.height / 2f))
                }
                if (withMarkers) {
                    // Inside the magnifier the markers are painted, not nodes: the same red numbered dots, at the magnified places.
                    for (marker in markers) {
                        val (px, py) = t.toPixels(marker.x * drawing.width, marker.y * drawing.height, scale)
                        val c = Offset(px.toFloat(), py.toFloat())
                        val a = if (marker.faded) 0.4f else 1f
                        drawCircle(dotColor, radius = MARKER_RADIUS.dp.toPx(), center = c, alpha = a)
                        marker.label?.let {
                            val laid = measurer.measure(it, markerStyle)
                            drawText(laid, topLeft = Offset(c.x - laid.size.width / 2f, c.y - laid.size.height / 2f), alpha = a)
                        }
                    }
                }
                dot?.let {
                    val (px, py) = t.toPixels(it.x * drawing.width, it.y * drawing.height, scale)
                    drawCircle(ringColor, radius = 8.dp.toPx(), center = Offset(px.toFloat(), py.toFloat()))
                    drawCircle(dotColor, radius = 6.dp.toPx(), center = Offset(px.toFloat(), py.toFloat()))
                }
            }

            Canvas(canvasModifier) { scene(current, highlight = null, withMarkers = false) }
            for (marker in markers) {
                val (px, py) = current.toPixels(marker.x * drawing.width, marker.y * drawing.height, scale)
                val half = with(density) { MARKER_RADIUS.dp.toPx() }
                if (px < -half || py < -half || px > widthPx + half || py > heightPx + half) continue
                var m = Modifier
                    .offset { IntOffset((px - half).roundToInt(), (py - half).roundToInt()) }
                    .size((MARKER_RADIUS * 2).dp)
                    .testTag(marker.tag)
                    .semantics {
                        marker.description?.let { stateDescription = it }
                        marker.label?.let { contentDescription = it }
                    }
                if (marker.onClick != null) m = m.clickable(onClick = marker.onClick)
                Box(
                    m.alpha(if (marker.faded) 0.4f else 1f).background(dotColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    marker.label?.let { Text(it, color = colors.onError, fontSize = 11.sp) }
                }
            }
            // The magnifier is drawn last, over the markers, so no dot ever sits on top of it (the dots under it show inside it).
            magnifierAt?.let { finger ->
                val r = with(density) { MAGNIFIER_RADIUS.dp.toPx() }
                val lift = with(density) { MAGNIFIER_LIFT.dp.toPx() }
                val above = Offset(
                    finger.x.coerceIn(r, (widthPx - r).toFloat().coerceAtLeast(r)),
                    (finger.y - r - lift).coerceAtLeast(r),
                )
                Canvas(Modifier.fillMaxWidth().aspectRatio(drawing.width.toFloat() / drawing.height)) {
                    val (ux, uy) = current.toUnits(finger.x.toDouble(), finger.y.toDouble(), scale)
                    val bigZoom = current.zoom * ViewTransform.MAGNIFIER_FACTOR
                    val k = scale * bigZoom
                    val big = ViewTransform(bigZoom, above.x - ux * k, above.y - uy * k)
                    // The page colour behind the parts, so the gaps between them show as they do on the drawing.
                    drawCircle(background, radius = r, center = above)
                    clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(above, r)) }) {
                        scene(big, highlight = aimedRegion, withMarkers = true)
                    }
                    drawCircle(selectedStroke, radius = r, center = above, style = Stroke(width = 2.dp.toPx()))
                    val cross = 8.dp.toPx()
                    val gap = 2.dp.toPx()
                    for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
                        val from = Offset(above.x + dx * gap, above.y + dy * gap)
                        val to = Offset(above.x + dx * cross, above.y + dy * cross)
                        drawLine(crossColor, from, to, strokeWidth = 2.dp.toPx())
                    }
                }
                Box(
                    Modifier.offset { IntOffset((above.x - r).roundToInt(), (above.y - r).roundToInt()) }
                        .size((MAGNIFIER_RADIUS * 2).dp)
                        .testTag("drawing-magnifier")
                        .semantics { stateDescription = aimedRegion ?: "" },
                )
            }
        }
    }
}

private const val MAGNIFIER_RADIUS = 56
private const val MAGNIFIER_LIFT = 24
private const val MARKER_RADIUS = 10

private fun polygon(points: List<Offset>, closed: Boolean): Path = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    if (closed) close()
}

/**
 * Pinch to zoom and drag to move (C31). Like `detectTransformGestures`, except that one finger moves the picture
 * only while it is zoomed in ([canDrag]): at normal size a one-finger swipe is left alone, so the screen
 * still scrolls when it starts on the drawing.
 */
private suspend fun PointerInputScope.detectZoomAndDrag(canDrag: () -> Boolean, onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit) {
    awaitEachGesture {
        var zoomAccum = 1f
        var panAccum = Offset.Zero
        var pastSlop = false
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val cancelled = event.changes.any { it.isConsumed }
            if (!cancelled) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                val several = event.changes.count { it.pressed } >= 2
                if (!pastSlop) {
                    zoomAccum *= zoomChange
                    panAccum += panChange
                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion = kotlin.math.abs(1 - zoomAccum) * centroidSize
                    val panMotion = panAccum.getDistance()
                    if (zoomMotion > viewConfiguration.touchSlop || (panMotion > viewConfiguration.touchSlop && (several || canDrag()))) pastSlop = true
                }
                if (pastSlop) {
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (zoomChange != 1f || panChange != Offset.Zero) onGesture(centroid, panChange, zoomChange)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (!cancelled && event.changes.any { it.pressed })
    }
}
