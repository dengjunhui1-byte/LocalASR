package com.djh.localasr

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.djh.localasr.core.AsrEngine
import com.djh.localasr.core.AsrPartialListener
import com.djh.localasr.core.AsrParams
import com.djh.localasr.core.AsrResult
import com.djh.localasr.core.AudioIo
import com.djh.localasr.core.EngineDescriptor
import com.djh.localasr.core.InstallProgress
import com.djh.localasr.core.MicCapture
import com.djh.localasr.core.ModelInstaller
import com.djh.localasr.core.ModelNotInstalledException
import com.djh.localasr.core.ModelSpec
import com.djh.localasr.core.UnsupportedDeviceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ModelStatus(
    val spec: ModelSpec,
    val installed: Boolean,
    val bytesOnDisk: Long,
)

data class InstallUiState(
    val title: String,
    val index: Int,
    val total: Int,
    val progress: InstallProgress? = null,
    val error: String? = null,
    val dismissible: Boolean = true,
)

data class UiState(
    val loading: Boolean = true,
    val engines: List<EngineDescriptor> = emptyList(),
    val selectedEngineId: String = "",
    val language: String = "zh",
    val params: Map<String, String> = emptyMap(),
    val busy: Boolean = false,
    val status: String = "",
    val error: String? = null,
    val transcript: String = "",
    val partialTranscript: String = "",
    val lastResult: AsrResult? = null,
    val install: InstallUiState? = null,
    val modelStatuses: Map<String, List<ModelStatus>> = emptyMap(),
    val recording: Boolean = false,
    val selectedAudioName: String = "",
) {
    val selectedEngine: EngineDescriptor?
        get() = engines.firstOrNull { it.id == selectedEngineId }
}

class AsrViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val engines = mutableMapOf<String, AsrEngine>()
    private val paramsByEngine = mutableMapOf<String, MutableMap<String, String>>()
    private val mic = MicCapture()
    private var decodeJob: Job? = null
    private var installJob: Job? = null
    private var micPumpJob: Job? = null
    private var pendingAudioPath: String? = null

    init {
        bootstrap()
    }

    private fun bootstrap() = viewModelScope.launch {
        val context = getApplication<Application>()
        val descriptors = withContext(Dispatchers.IO) {
            EngineCatalog.providers.mapNotNull { provider ->
                runCatching { EngineDescriptor.load(context, provider.engineId) }.getOrNull()
            }
        }
        for (descriptor in descriptors) {
            paramsByEngine[descriptor.id] =
                descriptor.params.associate { it.key to it.default }.toMutableMap()
        }
        if (descriptors.isEmpty()) {
            _state.update {
                it.copy(
                    loading = false,
                    error = "未能加载任何 ASR 引擎（请检查 APK 内 assets/asr/*/config.json）",
                )
            }
            return@launch
        }
        val first = descriptors.first()
        _state.update {
            it.copy(
                engines = descriptors,
                selectedEngineId = first.id,
                params = paramsByEngine.getValue(first.id).toMap(),
            )
        }
        val pending = withContext(Dispatchers.IO) {
            ModelInstaller.pendingStartupInstalls(context, descriptors)
        }
        if (pending.isEmpty()) {
            refreshModelStatuses()
            _state.update { it.copy(loading = false) }
        } else {
            runInstalls(pending, blocking = true)
        }
    }

    fun selectEngine(engineId: String) {
        if (!_state.value.engines.any { it.id == engineId }) return
        val params = paramsByEngine[engineId] ?: return
        _state.update {
            it.copy(
                selectedEngineId = engineId,
                params = params.toMap(),
                transcript = "",
                partialTranscript = "",
                lastResult = null,
                error = null,
            )
        }
    }

    fun selectLanguage(language: String) {
        _state.update { it.copy(language = language) }
    }

    fun setParam(key: String, value: String) {
        val engineId = _state.value.selectedEngineId
        paramsByEngine[engineId]?.set(key, value)
        _state.update { it.copy(params = paramsByEngine.getValue(engineId).toMap()) }
    }

    fun resetParams() {
        val engine = _state.value.selectedEngine ?: return
        paramsByEngine[engine.id] = engine.params.associate { it.key to it.default }.toMutableMap()
        _state.update { it.copy(params = paramsByEngine.getValue(engine.id).toMap()) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun onMicPermissionDenied() {
        _state.update {
            it.copy(error = "需要麦克风权限才能录音。请再次点击「麦克风录音」并在系统弹窗中允许。")
        }
    }

    fun onAudioPicked(uri: Uri) = viewModelScope.launch {
        val context = getApplication<Application>()
        _state.update { it.copy(busy = true, status = "正在读取音频…", error = null) }
        try {
            val dest = File(context.cacheDir, "picked-${System.currentTimeMillis()}.wav")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: error("无法打开所选文件")
            pendingAudioPath = dest.absolutePath
            _state.update {
                it.copy(
                    busy = false,
                    selectedAudioName = uri.lastPathSegment ?: dest.name,
                    status = "已选择：${dest.name}",
                )
            }
        } catch (e: Exception) {
            val msg = when {
                e.message?.contains("PCM WAV", ignoreCase = true) == true ->
                    "仅支持 16-bit PCM 单声道/立体声 WAV。请用其他 App 转换后再选，或改用麦克风录音。"
                else -> e.message ?: "读取失败"
            }
            _state.update { it.copy(busy = false, error = msg) }
        }
    }

    fun transcribeSelectedFile() {
        val path = pendingAudioPath ?: run {
            _state.update { it.copy(error = "请先选择 WAV 文件") }
            return
        }
        transcribeFile(path)
    }

    fun transcribeFile(path: String) {
        if (!isSelectedModelInstalled()) {
            _state.update { it.copy(error = modelNotInstalledMessage()) }
            return
        }
        decodeJob?.cancel()
        decodeJob = viewModelScope.launch {
            _state.update {
                it.copy(busy = true, status = "识别中…", error = null, partialTranscript = "")
            }
            try {
                val (samples, rate) = withContext(Dispatchers.IO) { AudioIo.readWavMono(path) }
                val result = withContext(Dispatchers.Default) {
                    engine().transcribeSamples(
                        samples,
                        rate,
                        _state.value.language,
                        params(),
                        object : AsrPartialListener {
                            override fun onPartial(partial: AsrResult): Boolean {
                                _state.update { s -> s.copy(partialTranscript = partial.text) }
                                return true
                            }
                        },
                    )
                }
                applyResult(result)
            } catch (e: ModelNotInstalledException) {
                _state.update { it.copy(error = e.message ?: modelNotInstalledMessage()) }
            } catch (e: UnsupportedDeviceException) {
                _state.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                val msg = when (e) {
                    is ModelNotInstalledException -> e.message ?: modelNotInstalledMessage()
                    else -> e.message ?: "识别失败"
                }
                _state.update { it.copy(error = msg) }
            } finally {
                _state.update { it.copy(busy = false, status = "") }
            }
        }
    }

    fun toggleRecording() {
        if (_state.value.recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (!isSelectedModelInstalled()) {
            _state.update {
                it.copy(error = modelNotInstalledMessage())
            }
            return
        }
        if (!mic.start()) {
            _state.update { it.copy(error = "无法启动麦克风（权限或硬件）") }
            return
        }
        val engine = engineOrNull()
        if (engine?.supportsLivePartial == true) {
            engine.beginLiveSession(_state.value.language, params()) { partial ->
                _state.update { it.copy(partialTranscript = partial.text) }
                true
            }
        }
        _state.update {
            it.copy(recording = true, partialTranscript = "", transcript = "", error = null)
        }
        micPumpJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive && mic.isRecording) {
                val chunk = mic.drain()
                if (chunk.isNotEmpty() && engine?.supportsLivePartial == true) {
                    engine.feedLiveAudio(chunk, AudioIo.TARGET_SAMPLE_RATE)
                }
                delay(50)
            }
        }
    }

    private fun stopRecording() {
        micPumpJob?.cancel()
        micPumpJob = null
        val samples = mic.stopAndTake()
        _state.update { it.copy(recording = false, status = "识别中…", busy = true) }
        decodeJob?.cancel()
        decodeJob = viewModelScope.launch {
            try {
                val engine = engine()
                val result = if (engine.supportsLivePartial) {
                    withContext(Dispatchers.Default) { engine.endLiveSession() }
                } else {
                    withContext(Dispatchers.Default) {
                        engine.transcribeSamples(
                            samples,
                            AudioIo.TARGET_SAMPLE_RATE,
                            _state.value.language,
                            params(),
                        )
                    }
                }
                applyResult(result)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "识别失败") }
            } finally {
                _state.update { it.copy(busy = false, status = "") }
            }
        }
    }

    private fun applyResult(result: AsrResult) {
        val extras = buildString {
            if (result.detectedLanguage.isNotBlank()) append("语言=${result.detectedLanguage} ")
            if (result.emotion.isNotBlank()) append("情感=${result.emotion} ")
            if (result.audioEvent.isNotBlank()) append("事件=${result.audioEvent} ")
        }
        _state.update {
            it.copy(
                transcript = result.text,
                partialTranscript = "",
                lastResult = result,
                status = buildString {
                    append("RTF≈${"%.2f".format(result.realTimeFactor)}")
                    if (extras.isNotBlank()) append(" · $extras")
                },
            )
        }
    }

    private fun params(): AsrParams =
        AsrParams.of(_state.value.params)

    private fun engineOrNull(): AsrEngine? = runCatching { engine() }.getOrNull()

    private fun engine(): AsrEngine {
        val context = getApplication<Application>()
        val id = _state.value.selectedEngineId
        if (id.isBlank()) error("未选择 ASR 引擎")
        val provider = EngineCatalog.providers.firstOrNull { it.engineId == id }
            ?: error("未知引擎：$id")
        return engines.getOrPut(id) { provider.create(context) }
    }

    fun isSelectedModelInstalled(): Boolean {
        val engine = _state.value.selectedEngine ?: return false
        val spec = engine.modelFor(_state.value.language) ?: return false
        return _state.value.modelStatuses[engine.id]
            ?.any { it.spec.id == spec.id && it.installed }
            ?: false
    }

    private fun modelNotInstalledMessage(): String {
        val engine = _state.value.selectedEngine
        val spec = engine?.modelFor(_state.value.language)
        return if (spec != null) {
            "「${spec.label}」尚未安装。请点击右上角设置 →「下载」，需联网。"
        } else {
            "当前引擎没有对应语言的模型，请换一个引擎或语言。"
        }
    }

    fun installModel(engineId: String, spec: ModelSpec) {
        val descriptor = _state.value.engines.first { it.id == engineId }
        runInstalls(listOf(descriptor to spec), blocking = false)
    }

    fun deleteModel(engineId: String, spec: ModelSpec) = viewModelScope.launch {
        engines.remove(engineId)?.release()
        withContext(Dispatchers.IO) {
            ModelInstaller.remove(getApplication(), engineId, spec)
        }
        refreshModelStatuses()
    }

    fun dismissInstall() {
        installJob?.cancel()
        _state.update { it.copy(install = null, loading = false) }
    }

    private fun runInstalls(items: List<Pair<EngineDescriptor, ModelSpec>>, blocking: Boolean) {
        installJob?.cancel()
        installJob = launchInstalls(items, blocking)
    }

    private fun launchInstalls(
        items: List<Pair<EngineDescriptor, ModelSpec>>,
        blocking: Boolean,
    ) = viewModelScope.launch {
        val context = getApplication<Application>()
        val title = if (blocking) "首次启动，正在导入内置模型" else "正在安装模型"
        _state.update {
            it.copy(
                loading = blocking,
                install = InstallUiState(title, 0, items.size, dismissible = !blocking),
            )
        }
        try {
            items.forEachIndexed { index, (engine, spec) ->
                engines.remove(engine.id)?.release()
                ModelInstaller.install(context, engine.id, spec) { progress ->
                    _state.update {
                        it.copy(
                            install = it.install?.copy(
                                index = index + 1,
                                progress = progress,
                            )
                        )
                    }
                }
            }
            refreshModelStatuses()
            _state.update { it.copy(install = null, loading = false) }
        } catch (e: Exception) {
            val message = when {
                e.message?.contains("Unable to resolve host", ignoreCase = true) == true ->
                    "网络不可用，无法下载模型。请检查 Wi‑Fi/移动数据后重试。"
                e.message?.contains("CLEARTEXT", ignoreCase = true) == true ->
                    "下载地址必须使用 HTTPS（${e.message}）"
                else -> e.message ?: "安装失败"
            }
            _state.update {
                it.copy(
                    install = it.install?.copy(error = message),
                    loading = false,
                )
            }
        }
    }

    private fun refreshModelStatuses() = viewModelScope.launch {
        val context = getApplication<Application>()
        val map = withContext(Dispatchers.IO) {
            _state.value.engines.associate { engine ->
                engine.id to engine.models.map { spec ->
                    ModelStatus(
                        spec = spec,
                        installed = ModelInstaller.isInstalled(context, engine.id, spec),
                        bytesOnDisk = ModelInstaller.installedBytes(context, engine.id, spec),
                    )
                }
            }
        }
        _state.update { it.copy(modelStatuses = map) }
    }

    override fun onCleared() {
        engines.values.forEach { it.release() }
        engines.clear()
        super.onCleared()
    }
}
