package com.djh.localasr

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.djh.localasr.ui.LocalAsrTheme
import com.djh.localasr.ui.MainScreen
import com.djh.localasr.ui.ModelInstallDialog
import com.djh.localasr.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LocalAsrTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val viewModel: AsrViewModel = viewModel()
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    var showSettings by remember { mutableStateOf(false) }

                    val pickAudio = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument(),
                    ) { uri -> uri?.let { viewModel.onAudioPicked(it) } }

                    val requestMic = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { granted ->
                        if (granted) {
                            viewModel.toggleRecording()
                        } else {
                            viewModel.onMicPermissionDenied()
                        }
                    }

                    if (showSettings) {
                        SettingsScreen(
                            state = state,
                            onBack = { showSettings = false },
                            onInstall = viewModel::installModel,
                            onDelete = viewModel::deleteModel,
                        )
                    } else {
                        MainScreen(
                            state = state,
                            isModelReady = viewModel.isSelectedModelInstalled(),
                            onSelectEngine = viewModel::selectEngine,
                            onSelectLanguage = viewModel::selectLanguage,
                            onParamChange = viewModel::setParam,
                            onResetParams = viewModel::resetParams,
                            onPickAudio = { pickAudio.launch(arrayOf("audio/*")) },
                            onTranscribeFile = viewModel::transcribeSelectedFile,
                            onToggleRecord = {
                                if (state.recording) {
                                    viewModel.toggleRecording()
                                } else {
                                    requestMic.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            onDismissError = viewModel::clearError,
                            onOpenSettings = { showSettings = true },
                        )
                    }

                    state.install?.let { install ->
                        ModelInstallDialog(
                            state = install,
                            onDismiss = viewModel::dismissInstall,
                        )
                    }
                }
            }
        }
    }
}
