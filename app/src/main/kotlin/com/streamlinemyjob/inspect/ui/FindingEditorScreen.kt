@file:OptIn(ExperimentalLayoutApi::class)

package com.streamlinemyjob.inspect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamlinemyjob.inspect.AppContainer
import com.streamlinemyjob.inspect.core.ActionChoice
import com.streamlinemyjob.inspect.core.FindingText
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Rules
import com.streamlinemyjob.inspect.core.Text as CoreText
import com.streamlinemyjob.inspect.data.Dot
import com.streamlinemyjob.inspect.data.OfferedList
import com.streamlinemyjob.inspect.data.OfferedValue
import com.streamlinemyjob.inspect.data.Ok
import com.streamlinemyjob.inspect.data.Refused
import com.streamlinemyjob.inspect.data.RepoCodes
import com.streamlinemyjob.inspect.data.TapResult
import kotlinx.coroutines.launch

/**
 * The finding editor (4.16, 4.25): the car drawing and its status line, area list tabs and chips, damage
 * chips, action chips with "No action" first, a note, Save, and (when editing) Delete.
 *
 * A tap on the drawing picks the area of the part under it and puts the dot there; picking a chip from
 * the lists clears the dot (C17); a dot can also be removed on its own (`dot-remove`).
 *
 * The area and damage must be picked. The action is optional (C3, 4.29): a new finding starts with no
 * action chip selected ("Action not chosen yet") and saves that way; tapping the selected chip again
 * unselects it.
 */
class FindingEditorViewModel(
    private val container: AppContainer,
    private val inspectionId: String,
    private val findingId: String?,
    private val startDot: Dot? = null,
) : ViewModel() {
    var loaded by mutableStateOf(false)
        private set
    var areaLists by mutableStateOf<List<OfferedList>>(emptyList())
        private set
    var areaValues by mutableStateOf<List<OfferedValue>>(emptyList())
        private set
    var damageValues by mutableStateOf<List<OfferedValue>>(emptyList())
        private set
    var actionValues by mutableStateOf<List<OfferedValue>>(emptyList())
        private set

    var area by mutableStateOf<String?>(null)
        private set
    var damage by mutableStateOf<String?>(null)

    /** The finding's dot (4.24): fractions of the drawing, or null. Saved with the finding. */
    var dot by mutableStateOf<Dot?>(null)
        private set

    /** The part to highlight: the one the current area came from (4.25). */
    var region by mutableStateOf<String?>(null)
        private set

    /** What the last tap or pick did, for the `drawing-status` line. */
    var status by mutableStateOf<DrawingStatus>(DrawingStatus.None)
        private set
    private var misses = 0

    /** The action state (4.29): a new finding starts "not chosen yet", with no chip selected. */
    var action by mutableStateOf<ActionChoice>(ActionChoice.NotChosen)
        private set

    var note by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
        private set

    /** Set while the same-area dialog is open: the question it asks. */
    var sameAreaQuestion by mutableStateOf<String?>(null)
        private set
    var confirmingDelete by mutableStateOf(false)

    fun load() {
        if (loaded) return
        viewModelScope.launch {
            val data = io {
                val repository = container.repository()
                val old = findingId?.let { repository.finding(it) }
                Loaded(
                    old,
                    repository.offeredLists(Role.AREA, old?.areaValueId),
                    repository.offeredValues(Role.AREA, old?.areaValueId),
                    repository.offeredValues(Role.DAMAGE, old?.damageValueId),
                    repository.offeredValues(Role.ACTION, old?.actionValueId),
                    resolveStart(repository, old),
                )
            }
            areaLists = data.areaLists
            areaValues = data.areaValues
            damageValues = data.damageValues
            actionValues = data.actionValues
            data.start?.let {
                area = it.area
                dot = it.dot
                region = it.region
                status = DrawingStatus.Area(it.label)
            }
            data.old?.let {
                area = it.areaValueId
                damage = it.damageValueId
                action = when {
                    it.actionValueId != null -> ActionChoice.Value(it.actionValueId)
                    it.noAction -> ActionChoice.NoAction
                    else -> ActionChoice.NotChosen
                }
                note = it.note ?: ""
            }
            loaded = true
        }
    }

    private class Loaded(
        val old: com.streamlinemyjob.inspect.data.Finding?,
        val areaLists: List<OfferedList>,
        val areaValues: List<OfferedValue>,
        val damageValues: List<OfferedValue>,
        val actionValues: List<OfferedValue>,
        val start: Start?,
    )

    /** What a finding starts with from its dot: the area it picks, the dot and the part. */
    private class Start(val area: String, val dot: Dot?, val region: String?, val label: String)

    /**
     * A new finding that began with a tap on the Inspection screen starts with that tap's area and dot (4.28).
     * An existing finding keeps its own dot; its part is highlighted only if the dot still resolves to a part
     * linked to the finding's area (4.25).
     */
    private fun resolveStart(repository: com.streamlinemyjob.inspect.data.Repository, old: com.streamlinemyjob.inspect.data.Finding?): Start? {
        if (old != null) {
            val oldDot = if (old.dotX != null && old.dotY != null && old.dotDrawing == container.drawing.id) Dot(old.dotX, old.dotY) else null
            val tap = oldDot?.let { repository.resolveTap(it.x, it.y) }
            val highlighted = (tap as? TapResult.Picked)?.takeIf { it.valueId == old.areaValueId }?.regionId
            return Start(old.areaValueId, oldDot, highlighted, repository.areaLabel(old.areaValueId) ?: "")
        }
        val d = startDot ?: return null
        val tap = repository.resolveTap(d.x, d.y) as? TapResult.Picked ?: return null
        return Start(tap.valueId, d, tap.regionId, repository.areaLabel(tap.valueId) ?: "")
    }

    /** The area chosen from the lists (4.25, C17): clears the dot, and the line shows the area. */
    fun pickArea(valueId: String) {
        area = valueId
        dot = null
        region = null
        viewModelScope.launch { status = DrawingStatus.Area(io { container.repository().areaLabel(valueId) } ?: "") }
    }

    /** Removes the dot and keeps the area (C17): a wrong dot is removed and the car tapped again. */
    fun removeDot() {
        dot = null
        region = null
    }

    /** A tap on the drawing at ([unitsX], [unitsY]) drawing units (4.25). */
    fun tapDrawing(unitsX: Double, unitsY: Double) {
        val drawing = container.drawing
        val fx = unitsX / drawing.width
        val fy = unitsY / drawing.height
        viewModelScope.launch {
            val (tap, label) = io {
                val repository = container.repository()
                val tap = repository.resolveTap(fx, fy)
                val value = when (tap) {
                    is TapResult.Picked -> tap.valueId
                    is TapResult.Removed -> tap.valueId
                    else -> null
                }
                tap to value?.let { repository.areaLabel(it) }
            }
            when (tap) {
                is TapResult.Picked -> {
                    area = tap.valueId
                    dot = Dot(fx, fy)
                    region = tap.regionId
                    status = DrawingStatus.Area(label ?: "")
                }
                is TapResult.NotLinked -> status = DrawingStatus.NotLinked(DrawingTexts.NOT_LINKED_EDITOR)
                is TapResult.Removed -> status = DrawingStatus.Removed(DrawingTexts.removedEditor(label ?: ""))
                TapResult.Outside -> status = DrawingStatus.Missed(++misses)
            }
        }
    }

    /** Taps an action chip (4.29, C36): a chip that is not selected is selected (and any other is unselected); the selected chip is unselected, so the action is not chosen yet again. */
    fun tapAction(tapped: ActionChoice) {
        action = if (action == tapped) ActionChoice.NotChosen else tapped
    }

    fun save(confirmedSameArea: Boolean, saved: () -> Unit) {
        val a = area
        val d = damage
        error = when {
            a == null -> "Pick an area."
            d == null -> "Pick a damage type."
            else -> null
        }
        if (a == null || d == null) return
        val choice = action
        val typedNote = note
        val dotNow = dot
        viewModelScope.launch {
            val result = io {
                val repository = container.repository()
                if (findingId == null) {
                    repository.addFinding(inspectionId, a, d, choice, typedNote, confirmedSameArea, dotNow)
                } else {
                    repository.editFinding(findingId, a, d, choice, typedNote, confirmedSameArea, dotNow)
                }
            }
            when (result) {
                is Ok -> {
                    sameAreaQuestion = null
                    saved()
                }
                is Refused -> if (result.code == RepoCodes.SAME_AREA_UNCONFIRMED) {
                    error = null
                    val existing = io { container.repository().resolvedFinding(result.detail) }
                    sameAreaQuestion = existing?.let { FindingText.sameAreaQuestion(it.view) } ?: "This area already has a finding. Add another?"
                } else {
                    sameAreaQuestion = null
                    error = Messages.forCode(result.code, result.detail)
                }
            }
        }
    }

    fun changeInstead() {
        sameAreaQuestion = null
    }

    fun delete(deleted: () -> Unit) {
        val id = findingId ?: return
        viewModelScope.launch {
            val result = io { container.repository().deleteFinding(id) }
            confirmingDelete = false
            when (result) {
                is Ok -> deleted()
                is Refused -> error = Messages.forCode(result.code, result.detail)
            }
        }
    }
}

@Composable
fun FindingEditorScreen(container: AppContainer, inspectionId: String, findingId: String?, done: () -> Unit, startDot: Dot? = null) {
    val vm: FindingEditorViewModel = viewModel { FindingEditorViewModel(container, inspectionId, findingId, startDot) }
    LaunchedEffect(Unit) { vm.load() }
    ScreenFrame(
        title = if (findingId == null) "New finding" else "Edit finding",
        actions = {
            if (findingId != null) {
                TextButton(onClick = { vm.confirmingDelete = true }, modifier = Modifier.testTag("finding-delete")) { Text("Delete") }
            }
        },
    ) {
        if (vm.loaded) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                CarDrawing(container.drawing, selectedRegion = vm.region, dot = vm.dot, onTap = vm::tapDrawing)
                DrawingStatusLine(vm.status)
                if (vm.dot != null) {
                    TextButton(onClick = vm::removeDot, modifier = Modifier.testTag("dot-remove")) { Text("Remove dot") }
                }
                Heading("Or pick from the list")
                AreaPicker(vm.areaLists, vm.areaValues, vm.area, onPick = vm::pickArea)
                Heading("Damage")
                Chips(vm.damageValues, vm.damage) { vm.damage = it }
                Heading("Action")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = vm.action == ActionChoice.NoAction,
                        onClick = { vm.tapAction(ActionChoice.NoAction) },
                        label = { Text(FindingText.NO_ACTION) },
                        modifier = Modifier.testTag("action-none"),
                    )
                    for (value in vm.actionValues) {
                        FilterChip(
                            selected = (vm.action as? ActionChoice.Value)?.valueId == value.valueId,
                            onClick = { vm.tapAction(ActionChoice.Value(value.valueId)) },
                            label = { Text(markedName(value.name, value.valueRemoved)) },
                            modifier = Modifier.testTag("value:${value.valueId}"),
                        )
                    }
                }
                OutlinedTextField(
                    value = vm.note,
                    onValueChange = { vm.note = it },
                    label = { Text("Note") },
                    supportingText = { Text("${CoreText.codePoints(vm.note)}/${Rules.NOTE_MAX}") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("note-input"),
                )
                ErrorText(vm.error)
                Row(Modifier.padding(vertical = 8.dp)) {
                    Button(onClick = { vm.save(false, done) }, modifier = Modifier.testTag("save")) { Text("Save") }
                }
            }
        }
    }
    vm.sameAreaQuestion?.let { text ->
        AlertDialog(
            onDismissRequest = vm::changeInstead,
            modifier = Modifier.testTag("warning-dialog"),
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { vm.save(true, done) }, modifier = Modifier.testTag("confirm")) { Text("Add another") } },
            dismissButton = { TextButton(onClick = vm::changeInstead, modifier = Modifier.testTag("cancel")) { Text("Change") } },
        )
    }
    if (vm.confirmingDelete) {
        AlertDialog(
            onDismissRequest = { vm.confirmingDelete = false },
            title = { Text("Delete this finding?") },
            confirmButton = { TextButton(onClick = { vm.delete(done) }, modifier = Modifier.testTag("confirm")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { vm.confirmingDelete = false }, modifier = Modifier.testTag("cancel")) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

/** One chip per offered value, tagged `value:<valueId>`. */
@Composable
private fun Chips(values: List<OfferedValue>, selected: String?, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (value in values) {
            FilterChip(
                selected = value.valueId == selected,
                onClick = { onPick(value.valueId) },
                label = { Text(markedName(value.name, value.valueRemoved)) },
                modifier = Modifier.testTag("value:${value.valueId}"),
            )
        }
    }
}
