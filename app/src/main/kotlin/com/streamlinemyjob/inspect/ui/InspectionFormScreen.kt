@file:OptIn(ExperimentalMaterial3Api::class)

package com.streamlinemyjob.inspect.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamlinemyjob.inspect.AppContainer
import com.streamlinemyjob.inspect.core.Dates
import com.streamlinemyjob.inspect.core.Mileage
import com.streamlinemyjob.inspect.core.Rules
import com.streamlinemyjob.inspect.data.Ok
import com.streamlinemyjob.inspect.data.Refused
import com.streamlinemyjob.inspect.data.RepoCodes
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Inspection form (4.16, 4.9): a new inspection for [vehicleId] (date defaults to today) or an edit
 * of [inspectionId]. A lower mileage than the previous inspection's opens the warning dialog; only
 * its Save calls the repository again with `confirmedLower = true`.
 */
class InspectionFormViewModel(
    private val container: AppContainer,
    private val vehicleId: String,
    private val inspectionId: String?,
) : ViewModel() {
    var date by mutableStateOf(Dates.format(LocalDate.now()))
    var mileage by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
        private set

    /** The lower-mileage warning's text while its dialog is open. */
    var warning by mutableStateOf<String?>(null)
        private set

    private var loaded = false

    fun load() {
        if (loaded || inspectionId == null) return
        loaded = true
        viewModelScope.launch {
            io { container.repository().inspection(inspectionId) }?.let {
                date = Dates.format(Dates.fromStored(it.date))
                mileage = it.mileage.toString()
            }
        }
    }

    fun save(confirmedLower: Boolean, saved: (String) -> Unit) {
        val typedDate = date
        val typedMileage = mileage
        viewModelScope.launch {
            val result = io {
                val repository = container.repository()
                if (inspectionId == null) {
                    repository.addInspection(vehicleId, typedDate, typedMileage, confirmedLower)
                } else {
                    repository.editInspection(inspectionId, typedDate, typedMileage, confirmedLower)
                }
            }
            when (result) {
                is Ok -> {
                    warning = null
                    saved(result.value)
                }
                is Refused -> if (result.code == RepoCodes.MILEAGE_LOWER_UNCONFIRMED) {
                    error = null
                    warning = lowerMileageText(typedMileage, result.detail)
                } else {
                    warning = null
                    error = Messages.forCode(result.code, result.detail)
                }
            }
        }
    }

    fun change() {
        warning = null
    }

    /** `Mileage 41,000 is lower than 45,210 on 12/10/2026. Save anyway?` (4.9). */
    private fun lowerMileageText(typedMileage: String, detail: String): String {
        val (previousMiles, previousDate) = detail.split(' ')
        val miles = (Mileage.parse(typedMileage) as Mileage.Ok).miles
        return "Mileage ${Mileage.grouped(miles)} is lower than ${Mileage.grouped(previousMiles.toInt())} " +
            "on ${Dates.format(Dates.fromStored(previousDate))}. Save anyway?"
    }
}

/** The picker's milliseconds for [date]: UTC midnight, as the picker gives them back. */
private fun pickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

@Composable
fun InspectionFormScreen(container: AppContainer, vehicleId: String, inspectionId: String?, saved: (String) -> Unit) {
    val vm: InspectionFormViewModel = viewModel { InspectionFormViewModel(container, vehicleId, inspectionId) }
    LaunchedEffect(Unit) { vm.load() }
    var picking by remember { mutableStateOf(false) }
    ScreenFrame(title = if (inspectionId == null) "New inspection" else "Edit inspection") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = vm.date,
                onValueChange = { vm.date = it },
                label = { Text("Date (DD/MM/YYYY)") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("date-input"),
            )
            TextButton(onClick = { picking = true }, modifier = Modifier.testTag("date-picker-open")) { Text("Calendar") }
        }
        OutlinedTextField(
            value = vm.mileage,
            onValueChange = { vm.mileage = it },
            label = { Text("Mileage") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("mileage-input"),
        )
        ErrorText(vm.error)
        Button(onClick = { vm.save(false, saved) }, modifier = Modifier.padding(vertical = 8.dp).testTag("save")) { Text("Save") }
    }
    if (picking) {
        val today = LocalDate.now()
        val typed = Dates.parse(vm.date, today)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = pickerMillis(if (typed is Dates.Ok) typed.date else today),
            yearRange = Rules.DATE_MIN.year..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Dates.fromPickerMillis(utcTimeMillis)
                    return !d.isBefore(Rules.DATE_MIN) && !d.isAfter(today)
                }

                override fun isSelectableYear(year: Int): Boolean = year in Rules.DATE_MIN.year..today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { vm.date = Dates.format(Dates.fromPickerMillis(it)) }
                        picking = false
                    },
                    modifier = Modifier.testTag("confirm"),
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }, modifier = Modifier.testTag("cancel")) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
    vm.warning?.let { text ->
        AlertDialog(
            onDismissRequest = vm::change,
            modifier = Modifier.testTag("warning-dialog"),
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { vm.save(true, saved) }, modifier = Modifier.testTag("confirm")) { Text("Save") } },
            dismissButton = { TextButton(onClick = vm::change, modifier = Modifier.testTag("cancel")) { Text("Change") } },
        )
    }
}
