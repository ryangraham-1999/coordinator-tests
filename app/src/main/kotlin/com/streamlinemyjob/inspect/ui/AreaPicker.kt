@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.streamlinemyjob.inspect.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.streamlinemyjob.inspect.core.FindingText
import com.streamlinemyjob.inspect.data.OfferedList
import com.streamlinemyjob.inspect.data.OfferedValue

/** A name with " (removed)" after it when it is removed (4.17). */
fun markedName(name: String, removed: Boolean): String = if (removed) name + FindingText.REMOVED else name

/**
 * The area part of the finding editor (4.16): the area lists as a scrollable row of tabs, and the
 * chosen list's areas as chips. It takes the offered [lists] and [values] (from `Repository`'s
 * `offeredLists` and `offeredValues`) and the [selected] value, and reports a pick through [onPick].
 * It knows no list or value names. The tab to start on is the selected value's list, else the first.
 * Tests use `area-list-tab:<listId>` and `value:<valueId>`. Its own piece so the drawing (S1.28) can
 * sit above it and the Region links screen (S1.29) can reuse it.
 */
@Composable
fun AreaPicker(
    lists: List<OfferedList>,
    values: List<OfferedValue>,
    selected: String?,
    onPick: (valueId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val startTab = lists.indexOfFirst { list -> values.any { it.valueId == selected && it.listId == list.listId } }.coerceAtLeast(0)
    var tab by remember(lists.map { it.listId }) { mutableIntStateOf(startTab) }
    // A pick made somewhere else (a tap on the drawing, 4.25) switches the tabs to the picked area's list.
    LaunchedEffect(selected) {
        val index = lists.indexOfFirst { list -> values.any { it.valueId == selected && it.listId == list.listId } }
        if (index >= 0) tab = index
    }
    if (lists.isEmpty()) return
    val current = lists[tab.coerceIn(0, lists.lastIndex)]
    androidx.compose.foundation.layout.Column(modifier) {
        PrimaryScrollableTabRow(selectedTabIndex = lists.indexOf(current), edgePadding = 0.dp) {
            lists.forEachIndexed { index, list ->
                Tab(
                    selected = list == current,
                    onClick = { tab = index },
                    text = { Text(markedName(list.name, list.removed)) },
                    modifier = Modifier.testTag("area-list-tab:${list.listId}"),
                )
            }
        }
        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (value in values.filter { it.listId == current.listId }) {
                FilterChip(
                    selected = value.valueId == selected,
                    onClick = { onPick(value.valueId) },
                    label = { Text(markedName(value.name, value.valueRemoved)) },
                    modifier = Modifier.testTag("value:${value.valueId}"),
                )
            }
        }
    }
}
