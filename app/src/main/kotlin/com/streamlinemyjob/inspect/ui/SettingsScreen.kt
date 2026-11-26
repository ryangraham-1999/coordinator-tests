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
import com.streamlinemyjob.inspect.data.Ok
import com.streamlinemyjob.inspect.data.Refused
import com.streamlinemyjob.inspect.data.SettingsList
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Settings (4.16): every list with its number of active values, and "Add area list" (4.13). */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    var lists by mutableStateOf<List<SettingsList>>(emptyList())
        private set
    var newName by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var loading: Job? = null

    /**
     * Reads the lists. A newer read cancels an older one still running, so an old read that finishes
     * late can never replace what a newer read (after an add) showed.
     */
    fun load() {
        loading?.cancel()
        loading = viewModelScope.launch { lists = io { container.repository().settingsLists() } }
    }

    fun typeName(text: String) {
        newName = text
    }

    fun addAreaList() {
        val typed = newName
        viewModelScope.launch {
            when (val result = io { container.repository().addAreaList(typed) }) {
                is Ok -> {
                    newName = ""
                    error = null
                    load()
                }
                is Refused -> error = Messages.forCode(result.code, result.detail, typed.trim())
            }
        }
    }
}

@Composable
fun SettingsScreen(container: AppContainer, openList: (String) -> Unit, openRegionLinks: () -> Unit) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    LaunchedEffect(Unit) { vm.load() }
    ScreenFrame(title = "Settings") {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            OutlinedTextField(
                value = vm.newName,
                onValueChange = vm::typeName,
                label = { Text("New area list") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("area-list-add-input"),
            )
            Button(onClick = vm::addAreaList, modifier = Modifier.padding(start = 8.dp).testTag("area-list-add")) { Text("Add area list") }
        }
        ErrorText(vm.error)
        LazyColumn(Modifier.fillMaxWidth().testTag("settings-scroll")) {
            items(vm.lists, key = { it.id }) { list ->
                Row(Modifier.fillMaxWidth().clickable { openList(list.id) }.padding(vertical = 12.dp).testTag("list-row:${list.id}")) {
                    Column {
                        Text(markedName(list.name, list.removed), style = MaterialTheme.typography.titleMedium)
                        Text("${list.activeValues} values", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider()
            }
            item(key = "car-drawing") {
                Row(Modifier.fillMaxWidth().clickable { openRegionLinks() }.padding(vertical = 12.dp).testTag("open-region-links")) {
                    Column {
                        Text("Car drawing", style = MaterialTheme.typography.titleMedium)
                        Text("Which area each part of the car picks", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
