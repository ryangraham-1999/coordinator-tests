package com.streamlinemyjob.inspect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import com.streamlinemyjob.inspect.core.Dates
import com.streamlinemyjob.inspect.core.Reg
import com.streamlinemyjob.inspect.data.Ok
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate

/** One row of the Vehicles screen: the reg display and the latest inspection date, if any. */
data class VehicleRowItem(val id: String, val regDisplay: String, val latest: LocalDate?)

/** Vehicles (the start screen, 4.16): search (4.8), rows, "New vehicle", "Add <REG>" from the search. */
class VehiclesViewModel(private val container: AppContainer) : ViewModel() {
    var query by mutableStateOf("")
        private set
    var rows by mutableStateOf<List<VehicleRowItem>>(emptyList())
        private set

    /** The reg display to offer as "Add <REG>": set when the search has no match and is a valid reg. */
    var addFromSearch by mutableStateOf<String?>(null)
        private set

    private var loading: Job? = null

    fun search(text: String) {
        query = text
        load()
    }

    fun load() {
        val q = query
        loading?.cancel()
        loading = viewModelScope.launch {
            val found = io {
                val repository = container.repository()
                repository.searchVehicles(q).map { v ->
                    VehicleRowItem(v.id, v.regDisplay, repository.inspectionsForVehicle(v.id).firstOrNull()?.let { Dates.fromStored(it.date) })
                }
            }
            rows = found
            val reg = Reg.check(q)
            addFromSearch = if (found.isEmpty() && q.isNotBlank() && reg is Reg.Ok) reg.display else null
        }
    }

    fun addFromSearch(opened: (String) -> Unit) {
        val q = query
        viewModelScope.launch {
            val result = io { container.repository().addVehicle(q) }
            if (result is Ok) opened(result.value) else load()
        }
    }
}

@Composable
fun VehiclesScreen(container: AppContainer, openVehicle: (String) -> Unit, newVehicle: () -> Unit, openSettings: () -> Unit) {
    val vm: VehiclesViewModel = viewModel { VehiclesViewModel(container) }
    LaunchedEffect(Unit) { vm.load() }
    ScreenFrame(
        title = "Vehicles",
        actions = {
            TextButton(onClick = openSettings, modifier = Modifier.testTag("open-settings")) { Text("Settings") }
        },
    ) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = vm::search,
            label = { Text("Search regs") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("vehicle-search"),
        )
        Button(onClick = newVehicle, modifier = Modifier.padding(vertical = 8.dp).testTag("vehicle-new")) { Text("New vehicle") }
        LazyColumn(Modifier.fillMaxWidth()) {
            vm.addFromSearch?.let { reg ->
                item(key = "add-from-search") {
                    Text(
                        "Add $reg",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable { vm.addFromSearch(openVehicle) }
                            .padding(vertical = 12.dp).testTag("vehicle-add-from-search"),
                    )
                }
            }
            items(vm.rows, key = { it.id }) { row ->
                Row(Modifier.fillMaxWidth().clickable { openVehicle(row.id) }.padding(vertical = 12.dp).testTag("vehicle-row:${row.id}")) {
                    Column {
                        Text(row.regDisplay, style = MaterialTheme.typography.titleMedium)
                        Text(row.latest?.let { Dates.format(it) } ?: "No inspections", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
