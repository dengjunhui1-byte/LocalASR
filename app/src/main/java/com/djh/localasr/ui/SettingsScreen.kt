package com.djh.localasr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.djh.localasr.ModelStatus
import com.djh.localasr.UiState
import com.djh.localasr.core.EngineDescriptor
import com.djh.localasr.core.ModelSource
import com.djh.localasr.core.ModelSpec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onInstall: (String, ModelSpec) -> Unit,
    onDelete: (String, ModelSpec) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.engines, key = { it.id }) { engine ->
                EngineCard(
                    engine = engine,
                    statuses = state.modelStatuses[engine.id].orEmpty(),
                    onInstall = onInstall,
                    onDelete = onDelete,
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngineCard(
    engine: EngineDescriptor,
    statuses: List<ModelStatus>,
    onInstall: (String, ModelSpec) -> Unit,
    onDelete: (String, ModelSpec) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(engine.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                engine.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // A plain Row squeezes the second chip into a sliver when the runtime name is
            // long, so let the chips wrap onto another line instead.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, enabled = false, label = { Text(engine.runtime) })
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(engine.languages.joinToString("/") { languageLabel(it) }) },
                )
            }

            statuses.forEach { status ->
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                ModelRow(engine.id, status, onInstall, onDelete)
            }
        }
    }
}

@Composable
private fun ModelRow(
    engineId: String,
    status: ModelStatus,
    onInstall: (String, ModelSpec) -> Unit,
    onDelete: (String, ModelSpec) -> Unit,
) {
    val spec = status.spec
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(spec.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    buildString {
                        append(if (status.installed) "已安装" else "未安装")
                        append(" · ")
                        append(
                            if (status.installed) formatBytes(status.bytesOnDisk)
                            else formatBytes(spec.sizeBytes)
                        )
                        append(" · ")
                        append(if (spec.source == ModelSource.ASSET) "内置" else "在线下载")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status.installed) {
                TextButton(onClick = { onDelete(engineId, spec) }) { Text("删除") }
            } else if (spec.installable) {
                OutlinedButton(onClick = { onInstall(engineId, spec) }) {
                    Text(if (spec.source == ModelSource.ASSET) "导入" else "下载")
                }
            }
        }

        if (!spec.installable && !status.installed) {
            Spacer(Modifier.height(8.dp))
            Text(
                "该模型无法自动下载。请在设置页使用「下载」，或运行 tools/fetch_models.sh 后 adb push 到 " +
                    "Android/data/com.djh.localasr/files/localasr/models/$engineId/${spec.dir}/",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
