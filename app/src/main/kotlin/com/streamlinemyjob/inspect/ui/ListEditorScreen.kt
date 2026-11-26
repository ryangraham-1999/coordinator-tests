package com.streamlinemyjob.inspect.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.data.Direction
import com.streamlinemyjob.inspect.data.ListDetail
import com.streamlinemyjob.inspect.data.Ok
import com.streamlinemyjob.inspect.data.Refused
import com.streamlinemyjob.inspect.data.Result
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The List editor (4.16): the list's name, its values in order, its removed values, and the 4.13 operations. */
class ListEditorViewModel(private val container: AppContainer, private val listId: String) : ViewModel() {
    var detail by mutableStateOf<ListDetail?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var listName by mutableStateOf("")
        private set
    var newValue by mutableStateOf("")
        private set

    /** The value being renamed, and the text typed so far. */
    var renaming by mutableStateOf<String?>(null)
        private set
    var renameText by mutableStateOf("")
        private set

    private var loading: Job? = null

    /** The first read when the screen opens; the name field is filled only if nothing has been typed. */
    fun load() = read(first = true)

    /** A newer read cancels an older one still running, so a late old read never replaces newer data. */
    private fun read(first: Boolean) {
        loading?.cancel()
        loading = viewModelScope.launch {
            val loaded = io { container.repository().listDetail(listId) }
            detail = loaded
            if (loaded != null && (!first || (renaming == null && listName.isEmpty()))) listName = loaded.list.name
        }
    }

    fun typeListName(text: String) { listName = text }
    fun typeNewValue(text: String) { newValue = text }
    fun typeRename(text: String) { renameText = text }

    private fun run(typed: String, after: () -> Unit = {}, call: () -> Result<String>) {
        viewModelScope.launch {
            when (val result = io { call() }) {
                is Ok -> {
                    error = null
                    after()
                    read(first = false)
                }
                is Refused -> error = Messages.forCode(result.code, result.detail, typed.trim())
            }
        }
    }

    fun renameList() = run(listName) { container.repository().renameList(listId, listName) }
    fun removeList() = run("") { container.repository().removeAreaList(listId) }
    fun restoreList() = run("") { container.repository().restoreAreaList(listId) }
    fun addValue() = run(newValue, after = { newValue = "" }) { container.repository().addValue(listId, newValue) }
    fun move(valueId: String, direction: Direction) = run("") { container.repository().moveValue(valueId, direction) }
    fun remove(valueId: String) = run("") { container.repository().removeValue(valueId) }
    fun restore(valueId: String) = run("") { container.repository().restoreValue(valueId) }

    /** First tap on a value's rename button opens the field with the current name; the next one saves it. */
    fun rename(valueId: String, currentName: String) {
        if (renaming != valueId) {
            renaming = valueId
            renameText = currentName
            error = null
        } else {
            val typed = renameText
            run(typed, after = { renaming = null }) { container.repository().renameValue(valueId, typed) }
        }
    }
}

@Composable
fun ListEditorScreen(container: AppContainer, listId: String) {
    val vm: ListEditorViewModel = viewModel(key = "list-editor:$listId") { ListEditorViewModel(container, listId) }
    LaunchedEffect(Unit) { vm.load() }
    val detail = vm.detail
    ScreenFrame(title = detail?.let { markedName(it.list.name, it.list.removed) } ?: "List") {
        if (detail == null) return@ScreenFrame
        val isArea = detail.list.role == Role.AREA
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            OutlinedTextField(
                value = vm.listName,
                onValueChange = vm::typeListName,
                label = { Text("List name") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("list-rename-input"),
            )
            Button(onClick = vm::renameList, modifier = Modifier.padding(start = 8.dp).testTag("list-rename")) { Text("Rename") }
        }
        if (isArea) {
            if (detail.list.removed) {
                Button(onClick = vm::restoreList, modifier = Modifier.testTag("list-restore")) { Text("Restore list") }
            } else {
                Button(onClick = vm::removeList, modifier = Modifier.testTag("list-remove")) { Text("Remove list") }
            }
        }
        ErrorText(vm.error)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            OutlinedTextField(
                value = vm.newValue,
                onValueChange = vm::typeNewValue,
                label = { Text("New value") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("value-add-input"),
            )
            Button(onClick = vm::addValue, modifier = Modifier.padding(start = 8.dp).testTag("value-add")) { Text("Add") }
        }
        LazyColumn(Modifier.fillMaxWidth().testTag("list-scroll")) {
            itemsIndexed(detail.active) { index, value ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("value-row:${value.id}")) {
                    Text(value.name, style = MaterialTheme.typography.titleMedium)
                    if (vm.renaming == value.id) {
                        OutlinedTextField(
                            value = vm.renameText,
                            onValueChange = vm::typeRename,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("value-rename-input"),
                        )
                    }
                    Row {
                        TextButton(onClick = { vm.rename(value.id, value.name) }, modifier = Modifier.testTag("value-rename:${value.id}")) {
                            Text(if (vm.renaming == value.id) "Save name" else "Rename")
                        }
                        TextButton(
                            onClick = { vm.move(value.id, Direction.UP) },
                            enabled = index > 0,
                            modifier = Modifier.testTag("value-up:${value.id}"),
                        ) { Text("Up") }
                        TextButton(
                            onClick = { vm.move(value.id, Direction.DOWN) },
                            enabled = index < detail.active.lastIndex,
                            modifier = Modifier.testTag("value-down:${value.id}"),
                        ) { Text("Down") }
                        TextButton(onClick = { vm.remove(value.id) }, modifier = Modifier.testTag("value-remove:${value.id}")) { Text("Remove") }
                    }
                    HorizontalDivider()
                }
            }
            if (detail.removed.isNotEmpty()) {
                item(key = "removed-heading") {
                    Text("Removed", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                }
                items(detail.removed, key = { "removed:${it.id}" }) { value ->
                    Row(Modifier.fillMaxWidth().testTag("removed-row:${value.id}")) {
                        Text(value.name, Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = { vm.restore(value.id) }, modifier = Modifier.testTag("value-restore:${value.id}")) { Text("Restore") }
                    }
                }
            }
        }
    }
}
