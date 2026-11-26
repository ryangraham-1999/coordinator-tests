package com.streamlinemyjob.inspect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import com.streamlinemyjob.inspect.core.Dates
import com.streamlinemyjob.inspect.core.FindingText
import com.streamlinemyjob.inspect.core.Mileage
import com.streamlinemyjob.inspect.data.Dot
import com.streamlinemyjob.inspect.data.Inspection
import com.streamlinemyjob.inspect.data.TapResult
import com.streamlinemyjob.inspect.data.Ok
import com.streamlinemyjob.inspect.data.Refused
import com.streamlinemyjob.inspect.data.ResolvedFinding
import kotlinx.coroutines.launch

/** Inspection (4.16): date, mileage, Edit, Delete, its findings in the 4.17 format and "Add finding". */
class InspectionViewModel(private val container: AppContainer, private val inspectionId: String) : ViewModel() {
    var inspection by mutableStateOf<Inspection?>(null)
        private set
    var reg by mutableStateOf("")
        private set
    var findings by mutableStateOf<List<ResolvedFinding>>(emptyList())
        private set
    var confirmingDelete by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
        private set

    /** What the last tap on the overview drawing did (4.28): a tap on no part shows the missed-tap warning. */
    var status by mutableStateOf<DrawingStatus>(DrawingStatus.None)
        private set
    private var misses = 0

    /**
     * A tap on the overview drawing at ([unitsX], [unitsY]) drawing units (4.28): a part linked to an offered area starts
     * a new finding with that dot and area ([startFinding]); any other tap shows what it did and starts nothing.
     */
    fun tapDrawing(unitsX: Double, unitsY: Double, startFinding: (Dot) -> Unit) {
        val drawing = container.drawing
        val fx = unitsX / drawing.width
        val fy = unitsY / drawing.height
        viewModelScope.launch {
            val (tap, label) = io {
                val repository = container.repository()
                val tap = repository.resolveTap(fx, fy)
                tap to (tap as? TapResult.Removed)?.let { repository.areaLabel(it.valueId) }
            }
            when (tap) {
                is TapResult.Picked -> {
                    status = DrawingStatus.None
                    startFinding(Dot(fx, fy))
                }
                is TapResult.NotLinked -> status = DrawingStatus.NotLinked(DrawingTexts.NOT_LINKED_INSPECTION)
                is TapResult.Removed -> status = DrawingStatus.Removed(DrawingTexts.removedInspection(label ?: ""))
                TapResult.Outside -> status = DrawingStatus.Missed(++misses)
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            val loaded = io {
                val repository = container.repository()
                val i = repository.inspection(inspectionId)
                Triple(i, i?.let { repository.vehicle(it.vehicleId)?.regDisplay } ?: "", repository.resolvedFindingsForInspection(inspectionId))
            }
            inspection = loaded.first
            reg = loaded.second
            findings = loaded.third
        }
    }

    fun delete(deleted: () -> Unit) {
        viewModelScope.launch {
            val result = io { container.repository().deleteInspection(inspectionId) }
            confirmingDelete = false
            when (result) {
                is Ok -> deleted()
                is Refused -> error = Messages.forCode(result.code, result.detail)
            }
        }
    }
}

@Composable
fun InspectionScreen(
    container: AppContainer,
    inspectionId: String,
    edit: (vehicleId: String) -> Unit,
    deleted: () -> Unit,
    addFinding: () -> Unit,
    openFinding: (findingId: String) -> Unit,
    addFindingAt: (Dot) -> Unit,
) {
    val vm: InspectionViewModel = viewModel { InspectionViewModel(container, inspectionId) }
    LaunchedEffect(Unit) { vm.load() }
    val inspection = vm.inspection
    ScreenFrame(
        title = vm.reg,
        actions = {
            TextButton(onClick = { inspection?.let { edit(it.vehicleId) } }, modifier = Modifier.testTag("inspection-edit")) { Text("Edit") }
            TextButton(onClick = { vm.confirmingDelete = true }, modifier = Modifier.testTag("inspection-delete")) { Text("Delete") }
        },
    ) {
        ErrorText(vm.error)
        if (inspection != null) {
            Text(Dates.format(Dates.fromStored(inspection.date)), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
            Text(Mileage.format(inspection.mileage), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = addFinding, modifier = Modifier.padding(vertical = 8.dp).testTag("finding-add")) { Text("Add finding") }
            LazyColumn(Modifier.fillMaxWidth()) {
                item(key = "overview") {
                    val markers = vm.findings.mapIndexedNotNull { index, resolved ->
                        val f = resolved.finding
                        if (f.dotX != null && f.dotY != null && f.dotDrawing == container.drawing.id) {
                            DrawingMarker("finding-dot:${index + 1}", f.dotX, f.dotY, "${index + 1}", onClick = { openFinding(f.id) })
                        } else {
                            null
                        }
                    }
                    Column {
                        CarDrawing(container.drawing, markers = markers, onTap = { x, y -> vm.tapDrawing(x, y, addFindingAt) })
                        DrawingStatusLine(vm.status)
                    }
                }
                itemsIndexed(vm.findings, key = { _, it -> it.finding.id }) { index, resolved ->
                    Row(
                        Modifier.fillMaxWidth().clickable { openFinding(resolved.finding.id) }.padding(vertical = 12.dp)
                            .testTag("finding-row:${resolved.finding.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 12.dp))
                        Text(FindingText.of(resolved.view), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        // The row's small dot icon (4.25, C23) when the finding has a dot.
                        if (resolved.finding.dotX != null) {
                            Box(Modifier.padding(start = 8.dp).size(12.dp).background(MaterialTheme.colorScheme.error, CircleShape).testTag("finding-dot:${resolved.finding.id}"))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (vm.confirmingDelete) {
        AlertDialog(
            onDismissRequest = { vm.confirmingDelete = false },
            title = { Text("Delete this inspection?") },
            text = { Text("Its findings are deleted with it.") },
            confirmButton = { TextButton(onClick = { vm.delete(deleted) }, modifier = Modifier.testTag("confirm")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { vm.confirmingDelete = false }, modifier = Modifier.testTag("cancel")) { Text("Cancel") } },
        )
    }
}
