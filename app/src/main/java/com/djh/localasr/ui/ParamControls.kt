package com.djh.localasr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.djh.localasr.core.ParamSpec
import com.djh.localasr.core.ParamType
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Renders one control from its JSON description.
 *
 * The whole point of the descriptor format is that engines declare their knobs and the
 * UI never has to know which engine it is talking to.
 */
@Composable
fun ParamControl(
    spec: ParamSpec,
    value: String,
    onChange: (String) -> Unit,
) {
    when (spec.type) {
        ParamType.FLOAT -> FloatParam(spec, value, onChange)
        ParamType.INT -> IntParam(spec, value, onChange)
        ParamType.ENUM -> EnumParam(spec, value, onChange)
        ParamType.BOOL -> BoolParam(spec, value, onChange)
    }
}

@Composable
private fun FloatParam(spec: ParamSpec, value: String, onChange: (String) -> Unit) {
    val current = value.toFloatOrNull() ?: spec.defaultFloat()
    val steps = if (spec.step > 0) {
        (((spec.max - spec.min) / spec.step).roundToInt() - 1).coerceAtLeast(0)
    } else {
        0
    }
    Column(Modifier.padding(vertical = 4.dp)) {
        LabelRow(spec.label, String.format(Locale.US, "%.2f", current), spec.help)
        Slider(
            value = current.coerceIn(spec.min.toFloat(), spec.max.toFloat()),
            onValueChange = { onChange(String.format(Locale.US, "%.3f", it)) },
            valueRange = spec.min.toFloat()..spec.max.toFloat(),
            steps = steps,
        )
    }
}

@Composable
private fun IntParam(spec: ParamSpec, value: String, onChange: (String) -> Unit) {
    val current = value.toDoubleOrNull()?.roundToInt() ?: spec.defaultInt()
    val step = spec.step.roundToInt().coerceAtLeast(1)
    val steps = (((spec.max - spec.min) / step).roundToInt() - 1).coerceAtLeast(0)
    Column(Modifier.padding(vertical = 4.dp)) {
        LabelRow(spec.label, current.toString(), spec.help)
        Slider(
            value = current.toFloat().coerceIn(spec.min.toFloat(), spec.max.toFloat()),
            onValueChange = { onChange(it.roundToInt().toString()) },
            valueRange = spec.min.toFloat()..spec.max.toFloat(),
            steps = steps,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumParam(spec: ParamSpec, value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 4.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = value.ifEmpty { "（默认）" },
                onValueChange = {},
                readOnly = true,
                label = { Text(spec.label) },
                supportingText = spec.help.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                spec.values.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.ifEmpty { "（默认）" }) },
                        onClick = {
                            onChange(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BoolParam(spec: ParamSpec, value: String, onChange: (String) -> Unit) {
    val current = value.toBooleanStrictOrNull() ?: spec.defaultBool()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(spec.label, style = MaterialTheme.typography.bodyLarge)
            if (spec.help.isNotBlank()) {
                Text(
                    spec.help,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = current, onCheckedChange = { onChange(it.toString()) })
    }
}

@Composable
private fun LabelRow(label: String, value: String, help: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (help.isNotBlank()) {
                Text(
                    help,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
        )
    }
}
