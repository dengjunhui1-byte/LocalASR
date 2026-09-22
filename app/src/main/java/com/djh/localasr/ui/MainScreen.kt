package com.djh.localasr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.djh.localasr.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: UiState,
    onSelectEngine: (String) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onParamChange: (String, String) -> Unit,
    onResetParams: () -> Unit,
    onPickAudio: () -> Unit,
    onTranscribeFile: () -> Unit,
    onToggleRecord: () -> Unit,
    onDismissError: () -> Unit,
    onOpenSettings: () -> Unit,
    isModelReady: Boolean,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本地 ASR") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "模型管理")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在准备模型…")
            }
            return@Scaffold
        }

        if (state.engines.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    state.error ?: "没有可用的 ASR 引擎",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EngineDropdown(state, onSelectEngine)
            LanguageRow(state.language, onSelectLanguage)

            if (!isModelReady) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "当前引擎模型尚未安装",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "本 APK 不含权重文件。请点右上角齿轮进入「模型管理」，为当前引擎点击「下载」（需联网），完成后再录音或识别。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                            Text("去下载模型")
                        }
                    }
                }
            }

            state.selectedEngine?.let { engine ->
                if (engine.flagship || engine.parameterCount.isNotBlank()) {
                    Text(
                        buildString {
                            if (engine.flagship) append("旗舰 · ")
                            if (engine.parameterCount.isNotBlank()) append(engine.parameterCount)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    engine.sampleHint(state.language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("识别结果", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    val shown = when {
                        state.partialTranscript.isNotBlank() -> state.partialTranscript
                        state.transcript.isNotBlank() -> state.transcript
                        else -> "（等待音频输入）"
                    }
                    Text(
                        shown,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                    state.lastResult?.timestamps?.takeIf { it.isNotEmpty() }?.let { ts ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "时间戳数量：${ts.size}（引擎支持时显示）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.status.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(state.status, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onPickAudio, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                    Text(" 选择 WAV")
                }
                Button(
                    onClick = onTranscribeFile,
                    enabled = !state.busy && state.selectedAudioName.isNotBlank() && isModelReady,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("识别文件")
                }
            }
            if (state.selectedAudioName.isNotBlank()) {
                Text("已选：${state.selectedAudioName}", style = MaterialTheme.typography.bodySmall)
            }

            FilledTonalButton(
                onClick = onToggleRecord,
                enabled = !state.busy && (state.recording || isModelReady),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    if (state.recording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = null,
                )
                Text(if (state.recording) " 停止并识别" else " 麦克风录音")
            }

            ParamSection(state, onParamChange, onResetParams)

            state.error?.let { err ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(err, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        OutlinedButton(onClick = onDismissError) { Text("关闭") }
                    }
                }
            }

            if (state.busy && !state.recording) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp))
                    Text("处理中…")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineDropdown(state: UiState, onSelectEngine: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val engine = state.selectedEngine
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        androidx.compose.material3.OutlinedTextField(
            value = engine?.displayName ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("引擎") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.engines.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.displayName) },
                    onClick = {
                        onSelectEngine(item.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun LanguageRow(selected: String, onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf("zh", "en").forEachIndexed { index, code ->
            SegmentedButton(
                selected = selected == code,
                onClick = { onSelect(code) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
            ) {
                Text(languageLabel(code))
            }
        }
    }
}

@Composable
private fun ParamSection(
    state: UiState,
    onParamChange: (String, String) -> Unit,
    onResetParams: () -> Unit,
) {
    val engine = state.selectedEngine ?: return
    if (engine.params.isEmpty()) return
    Text("参数", style = MaterialTheme.typography.titleMedium)
    engine.params.forEach { spec ->
        ParamControl(
            spec = spec,
            value = state.params[spec.key] ?: spec.default,
            onChange = { onParamChange(spec.key, it) },
        )
    }
    OutlinedButton(onClick = onResetParams) { Text("恢复默认参数") }
}
