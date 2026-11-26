package com.streamlinemyjob.inspect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import com.streamlinemyjob.inspect.core.Hit
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.data.OfferedList
import com.streamlinemyjob.inspect.data.OfferedValue
import com.streamlinemyjob.inspect.data.Ok
import com.streamlinemyjob.inspect.data.Refused
import kotlinx.coroutines.launch

/**
 * The Region links screen (designs/stage-1-foundation.md 4.25, S1.29): the drawing; a tap selects a region and
 * `link-current` says what it picks; `Change` opens the area tabs and chips (offered values only) and a pick links
 * the region at once; `Unlink` sets it to not linked. A tap on no region clears the selection and shows the
 * missed-tap warning instead.
 */
class RegionLinksViewModel(private val container: AppContainer) : ViewModel() {
    /** The selected region, or null. */
    var region by mutableStateOf<String?>(null)
        private set

    /** What the selected region picks: its link as `Linked to: ...`, `Not linked`, or null when nothing is selected. */
    var current by mutableStateOf<String?>(null)
        private set

    /** The value the selected region is linked to (any, removed or not), or null. */
    var linkedValue by mutableStateOf<String?>(null)
        private set
    var missed by mutableStateOf<DrawingStatus?>(null)
        private set
    var changing by mutableStateOf(false)
        private set
    var areaLists by mutableStateOf<List<OfferedList>>(emptyList())
        private set
    var areaValues by mutableStateOf<List<OfferedValue>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var misses = 0

    /** A tap at ([unitsX], [unitsY]) drawing units. */
    fun tap(unitsX: Double, unitsY: Double) {
        val hit = Hit.resolve(container.drawing, unitsX, unitsY)
        error = null
        changing = false
        if (hit == null) {
            region = null
            current = null
            linkedValue = null
            missed = DrawingStatus.Missed(++misses)
        } else {
            missed = null
            select(hit)
        }
    }

    private fun select(regionId: String) {
        region = regionId
        viewModelScope.launch {
            val (valueId, label) = io {
                val repository = container.repository()
                val value = repository.regionLinks()[regionId]
                value to value?.let { repository.areaLabel(it) }
            }
            if (region != regionId) return@launch
            linkedValue = valueId
            // The area is written as 4.17 writes it, which already marks a removed value or list.
            current = if (valueId == null) "Not linked" else "Linked to: $label"
        }
    }

    fun change() {
        viewModelScope.launch {
            val (lists, values) = io {
                val repository = container.repository()
                repository.offeredLists(Role.AREA) to repository.offeredValues(Role.AREA)
            }
            areaLists = lists
            areaValues = values
            changing = true
        }
    }

    fun pick(valueId: String) {
        val regionId = region ?: return
        viewModelScope.launch {
            when (val result = io { container.repository().linkRegion(regionId, valueId) }) {
                is Ok -> {
                    error = null
                    changing = false
                    select(regionId)
                }
                is Refused -> error = Messages.forCode(result.code, result.detail)
            }
        }
    }

    fun unlink() {
        val regionId = region ?: return
        viewModelScope.launch {
            when (val result = io { container.repository().unlinkRegion(regionId) }) {
                is Ok -> {
                    error = null
                    changing = false
                    select(regionId)
                }
                is Refused -> error = Messages.forCode(result.code, result.detail)
            }
        }
    }
}

@Composable
fun RegionLinksScreen(container: AppContainer) {
    val vm: RegionLinksViewModel = viewModel { RegionLinksViewModel(container) }
    ScreenFrame(title = "Car drawing") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            CarDrawing(container.drawing, selectedRegion = vm.region, onTap = vm::tap)
            val missed = vm.missed
            if (missed != null) {
                DrawingStatusLine(missed)
            } else if (vm.current != null) {
                Text(vm.current!!, modifier = Modifier.padding(vertical = 8.dp).testTag("link-current"))
            } else {
                DrawingStatusLine(DrawingStatus.None)
            }
            ErrorText(vm.error)
            if (vm.region != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::change, modifier = Modifier.testTag("link-change")) { Text("Change") }
                    if (vm.linkedValue != null) {
                        OutlinedButton(onClick = vm::unlink, modifier = Modifier.testTag("link-unlink")) { Text("Unlink") }
                    }
                }
            }
            if (vm.changing) {
                AreaPicker(
                    vm.areaLists, vm.areaValues, vm.linkedValue, onPick = vm::pick,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
