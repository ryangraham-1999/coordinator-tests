package com.streamlinemyjob.inspect.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** What the last tap on a drawing did (designs/stage-1-foundation.md 4.25); shown on the `drawing-status` line. */
sealed interface DrawingStatus {
    /** Nothing tapped yet and no area. */
    data object None : DrawingStatus

    /** The area as 4.17 writes it (a tap picked it, a chip was picked, or the finding was opened). */
    data class Area(val text: String) : DrawingStatus

    /** A tap on a part that is not linked. */
    data class NotLinked(val text: String) : DrawingStatus

    /** A tap on a part linked to a removed value: the text names that value as 4.17 writes it. */
    data class Removed(val text: String) : DrawingStatus

    /** A tap on no part: the missed-tap warning. [count] changes on every miss so the phone vibrates each time. */
    data class Missed(val count: Int) : DrawingStatus
}

object DrawingTexts {
    const val MISSED = "Missed: that tap was between parts. Nothing was picked. Tap inside a part."
    const val NOT_LINKED_EDITOR = "This part is not linked to an area. Pick from the list below, or link it in Settings."
    const val NOT_LINKED_INSPECTION = "This part is not linked to an area. Link it in Settings, or use Add finding."
    fun removedEditor(area: String) = "$area is removed. Restore it in Settings, or pick from the list below."
    fun removedInspection(area: String) = "$area is removed. Restore it in Settings, or use Add finding."
}

/** The line under a drawing (test tag `drawing-status`); the missed-tap warning adds its icon (`drawing-missed`) and one short vibration. */
@Composable
fun DrawingStatusLine(status: DrawingStatus, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    if (status is DrawingStatus.Missed) {
        LaunchedEffect(status.count) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
    Row(modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (status is DrawingStatus.Missed) {
            Icon(
                WarningIcon,
                contentDescription = "Warning",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp).testTag("drawing-missed"),
            )
        }
        val text = when (status) {
            DrawingStatus.None -> " "
            is DrawingStatus.Area -> status.text
            is DrawingStatus.NotLinked -> status.text
            is DrawingStatus.Removed -> status.text
            is DrawingStatus.Missed -> DrawingTexts.MISSED
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (status is DrawingStatus.Missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = if (status is DrawingStatus.Missed) 6.dp else 0.dp).testTag("drawing-status"),
        )
    }
}

/**
 * The warning icon (4.25 names `Icons.Filled.Warning`): the same Material shape drawn here, because the icon library is
 * not one of the app's dependencies (4.1) and a builder does not add one.
 */
private val WarningIcon: ImageVector = ImageVector.Builder("Warning", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = androidx.compose.ui.graphics.SolidColor(Color.Black), pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd) {
        moveTo(1f, 21f); horizontalLineToRelative(22f); lineTo(12f, 2f); close()
        moveTo(13f, 18f); horizontalLineToRelative(-2f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); close()
        moveTo(13f, 14f); horizontalLineToRelative(-2f); verticalLineToRelative(-4f); horizontalLineToRelative(2f); close()
    }
}.build()
