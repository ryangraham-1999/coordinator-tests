package com.streamlinemyjob.inspect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import com.streamlinemyjob.inspect.core.Mileage
import com.streamlinemyjob.inspect.data.Inspection
import com.streamlinemyjob.inspect.data.Ok
import com.streamlinemyjob.inspect.data.Refused
import com.streamlinemyjob.inspect.data.Vehicle
import com.streamlinemyjob.inspect.data.VehicleCounts
import kotlinx.coroutines.launch

/** Vehicle (4.16): reg, Edit, Delete with counts, inspections (date, mileage), "New inspection". */
class VehicleViewModel(private val container: AppContainer, private val vehicleId: String) : ViewModel() {
    var vehicle by mutableStateOf<Vehicle?>(null)
        private set
    var inspections by mutableStateOf<List<Inspection>>(emptyList())
        private set

    /** Every dot of every inspection of the vehicle (4.28), the current inspection's first-listed ones marked. */
    var dots by mutableStateOf<List<VehicleDot>>(emptyList())
        private set

    /** Set while the delete dialog is open: what the delete would remove (`countsForVehicle`). */
    var deleteCounts by mutableStateOf<VehicleCounts?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun load() {
        viewModelScope.launch {
            val loaded = io {
                val repository = container.repository()
                val list = repository.inspectionsForVehicle(vehicleId)
                // The current inspection is the first one listed (the newest); the others are older (4.28).
                val dots = list.flatMapIndexed { index, inspection ->
                    repository.findingsForInspection(inspection.id).mapNotNull { f ->
                        if (f.dotX != null && f.dotY != null && f.dotDrawing == container.drawing.id) VehicleDot(f.id, f.dotX, f.dotY, index == 0) else null
                    }
                }
                Triple(repository.vehicle(vehicleId), list, dots)
            }
            vehicle = loaded.first
            inspections = loaded.second
            dots = loaded.third
        }
    }

    fun askDelete() {
        viewModelScope.launch { deleteCounts = io { container.repository().countsForVehicle(vehicleId) } }
    }

    fun cancelDelete() {
        deleteCounts = null
    }

    fun delete(deleted: () -> Unit) {
        viewModelScope.launch {
            val result = io { container.repository().deleteVehicle(vehicleId) }
            deleteCounts = null
            when (result) {
                is Ok -> deleted()
                is Refused -> error = Messages.forCode(result.code, result.detail)
            }
        }
    }
}

/** A dot on the Vehicle screen's drawing: [current] when it is on the current inspection, else older (paler). */
class VehicleDot(val findingId: String, val x: Double, val y: Double, val current: Boolean)

/** "3 inspections", "1 finding". */
fun countOf(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

@Composable
fun VehicleScreen(
    container: AppContainer,
    vehicleId: String,
    edit: () -> Unit,
    deleted: () -> Unit,
    openInspection: (String) -> Unit,
    newInspection: () -> Unit,
) {
    val vm: VehicleViewModel = viewModel { VehicleViewModel(container, vehicleId) }
    LaunchedEffect(Unit) { vm.load() }
    val reg = vm.vehicle?.regDisplay ?: ""
    ScreenFrame(
        title = reg,
        actions = {
            TextButton(onClick = edit, modifier = Modifier.testTag("vehicle-edit")) { Text("Edit") }
            TextButton(onClick = vm::askDelete, modifier = Modifier.testTag("vehicle-delete")) { Text("Delete") }
        },
    ) {
        ErrorText(vm.error)
        Button(onClick = newInspection, modifier = Modifier.padding(vertical = 8.dp).testTag("inspection-new")) { Text("New inspection") }
        if (vm.vehicle != null && vm.inspections.isEmpty()) Text("No inspections", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.fillMaxWidth()) {
            item(key = "overview") {
                CarDrawing(
                    container.drawing,
                    markers = vm.dots.map {
                        DrawingMarker("vehicle-dot:${it.findingId}", it.x, it.y, null, faded = !it.current, description = if (it.current) "current" else "older")
                    },
                )
            }
            items(vm.inspections, key = { it.id }) { inspection ->
                Row(Modifier.fillMaxWidth().clickable { openInspection(inspection.id) }.padding(vertical = 12.dp).testTag("inspection-row:${inspection.id}")) {
                    Column {
                        Text(Dates.format(Dates.fromStored(inspection.date)), style = MaterialTheme.typography.titleMedium)
                        Text(Mileage.format(inspection.mileage), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider()
            }
        }
    }
    vm.deleteCounts?.let { counts ->
        AlertDialog(
            onDismissRequest = vm::cancelDelete,
            title = { Text("Delete $reg?") },
            text = {
                Text(
                    "This also deletes its ${countOf(counts.inspections, "inspection", "inspections")} " +
                        "and ${countOf(counts.findings, "finding", "findings")}.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.delete(deleted) }, modifier = Modifier.testTag("confirm")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = vm::cancelDelete, modifier = Modifier.testTag("cancel")) { Text("Cancel") } },
        )
    }
}
