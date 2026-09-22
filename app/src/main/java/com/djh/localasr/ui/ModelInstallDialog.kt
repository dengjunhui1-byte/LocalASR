package com.djh.localasr.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.djh.localasr.InstallUiState
import com.djh.localasr.core.InstallPhase

/**
 * The one-time import gate.
 *
 * On first launch the bundled models have to be unpacked out of the APK before any
 * engine can open them, so this blocks the app until it finishes. Later on-demand
 * downloads reuse the same dialog but stay dismissible.
 */
@Composable
fun ModelInstallDialog(
    state: InstallUiState,
    onDismiss: () -> Unit,
) {
    val progress = state.progress
    AlertDialog(
        onDismissRequest = { if (state.dismissible) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = state.dismissible,
            dismissOnClickOutside = false,
        ),
        title = { Text(if (state.error != null) "模型准备失败" else state.title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (state.error != null) {
                    Text(state.error, style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }

                Text(
                    "${phaseLabel(progress?.phase)} ${state.index}/${state.total} · " +
                        (progress?.modelLabel ?: "准备中"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))

                if (progress != null && progress.bytesTotal > 0) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${formatBytes(progress.bytesDone)} / ${formatBytes(progress.bytesTotal)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                if (progress != null && progress.currentFile.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        progress.currentFile,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "下载/导入完成后，模型保存在 App 外部存储（Android/data/…/localasr/models/），通常只需一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (state.dismissible || state.error != null) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

private fun phaseLabel(phase: InstallPhase?): String = when (phase) {
    InstallPhase.COPYING -> "正在导入"
    InstallPhase.DOWNLOADING -> "正在下载"
    InstallPhase.EXTRACTING -> "正在解压"
    InstallPhase.VERIFYING -> "正在校验"
    null -> "正在准备"
}
