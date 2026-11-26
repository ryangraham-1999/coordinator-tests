package com.streamlinemyjob.inspect.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamlinemyjob.inspect.AppContainer
import com.streamlinemyjob.inspect.core.Codes
import com.streamlinemyjob.inspect.data.Ok
import com.streamlinemyjob.inspect.data.Refused
import kotlinx.coroutines.launch

/** Vehicle form (4.16): a new vehicle, or a new reg for [vehicleId]. */
class VehicleFormViewModel(private val container: AppContainer, private val vehicleId: String?) : ViewModel() {
    var reg by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
        private set

    /** On `REG_TAKEN`: the existing vehicle's id and reg display, for the "Open <REG>" button. */
    var existing by mutableStateOf<Pair<String, String>?>(null)
        private set

    private var loaded = false

    fun load() {
        if (loaded || vehicleId == null) return
        loaded = true
        viewModelScope.launch {
            io { container.repository().vehicle(vehicleId) }?.let { reg = it.regDisplay }
        }
    }

    fun save(saved: (String) -> Unit) {
        val typed = reg
        viewModelScope.launch {
            val result = io {
                val repository = container.repository()
                if (vehicleId == null) repository.addVehicle(typed) else repository.renameVehicle(vehicleId, typed)
            }
            when (result) {
                is Ok -> saved(result.value)
                is Refused -> {
                    error = Messages.forCode(result.code, result.detail)
                    existing = if (result.code == Codes.REG_TAKEN) {
                        io { container.repository().vehicle(result.detail) }?.let { it.id to it.regDisplay }
                    } else {
                        null
                    }
                }
            }
        }
    }
}

@Composable
fun VehicleFormScreen(container: AppContainer, vehicleId: String?, saved: (String) -> Unit, openExisting: (String) -> Unit) {
    val vm: VehicleFormViewModel = viewModel { VehicleFormViewModel(container, vehicleId) }
    LaunchedEffect(Unit) { vm.load() }
    ScreenFrame(title = if (vehicleId == null) "New vehicle" else "Edit reg") {
        OutlinedTextField(
            value = vm.reg,
            onValueChange = { vm.reg = it },
            label = { Text("Reg") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth().testTag("reg-input"),
        )
        ErrorText(vm.error)
        vm.existing?.let { (id, display) ->
            OutlinedButton(onClick = { openExisting(id) }, modifier = Modifier.testTag("open-existing")) { Text("Open $display") }
        }
        Button(onClick = { vm.save(saved) }, modifier = Modifier.padding(vertical = 8.dp).testTag("save")) { Text("Save") }
    }
}
