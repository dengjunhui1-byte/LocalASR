package com.djh.localasr.qwenasr

import android.content.Context
import com.djh.localasr.core.AsrEngine
import com.djh.localasr.core.AsrEngineProvider
import com.djh.localasr.core.AsrParams
import com.djh.localasr.core.AsrPartialListener
import com.djh.localasr.core.AsrResult
import com.djh.localasr.core.AudioIo
import com.djh.localasr.core.BaseAsrEngine
import com.djh.localasr.core.EngineDescriptor
import com.djh.localasr.core.writeWav
import java.io.File
import kotlin.system.measureTimeMillis

const val QWENASR_ENGINE_ID = "qwenasr"

object QwenAsrProvider : AsrEngineProvider {
    override val engineId: String = QWENASR_ENGINE_ID
    override fun create(context: Context): AsrEngine =
        QwenAsrEngine(context, EngineDescriptor.load(context, QWENASR_ENGINE_ID))
}

/**
 * Qwen3-ASR-0.6B (int8 MNN) via self-contained libqwenasr_jni.so.
 * Weights are fetched on demand from the hb-dev Hugging Face MNN pack (see config.json).
 */
class QwenAsrEngine(
    context: Context,
    descriptor: EngineDescriptor,
) : BaseAsrEngine(context, descriptor) {

    private val requiredFiles = listOf(
        "config.json",
        "llm_config.json",
        "tokenizer.txt",
        "llm.mnn",
        "llm.mnn.weight",
        "embeddings_bf16.bin",
        "audio.mnn",
        "audio.mnn.weight",
    )

    private var runtimeKey: String? = null

    override fun transcribeSamples(
        samples: FloatArray,
        sampleRate: Int,
        language: String,
        params: AsrParams,
        partialListener: AsrPartialListener?,
    ): AsrResult {
        requireSupportedAbi()
        QwenAsrNative.requireLoaded()

        if (!isInstalled(context, language)) {
            throw com.djh.localasr.core.ModelNotInstalledException(
                descriptor.id,
                "${descriptor.displayName} 模型尚未安装，请先在设置页完成导入",
            )
        }

        val missing = missingFiles(language)
        if (missing.isNotEmpty()) {
            throw com.djh.localasr.core.ModelNotInstalledException(
                descriptor.id,
                "Qwen3-ASR 模型未安装完整，缺少：${missing.joinToString()}\n" +
                    "目录：${modelDir(language).absolutePath}",
            )
        }

        val pcm = AudioIo.prepareForAsr(samples, sampleRate)
        val audioSeconds = pcm.size.toFloat() / AudioIo.TARGET_SAMPLE_RATE
        val dir = modelDir(language)
        val tmpRoot = File(context.cacheDir, "qwenasr-tmp").apply { mkdirs() }
        val wav = File(tmpRoot, "input-${System.nanoTime()}.wav")
        writeWav(wav, pcm, AudioIo.TARGET_SAMPLE_RATE)

        val threads = params.int("numThreads", 4).coerceIn(1, 8)
        val asrLanguage = mapAsrLanguage(language, params)
        val maxTokens = params.int("maxNewTokens", 512).coerceIn(32, 2048)
        val key = "${dir.absolutePath}|$threads|$asrLanguage"

        lateinit var text: String
        val ms = measureTimeMillis {
            if (runtimeKey != key) {
                QwenAsrNative.nativeRelease()
                QwenAsrNative.nativeEnsureLoaded(
                    configPath = File(dir, "config.json").absolutePath,
                    tmpPath = tmpRoot.absolutePath,
                    threads = threads,
                    asrLanguage = asrLanguage,
                )
                runtimeKey = key
            }

            val raw = QwenAsrNative.nativeRecognize(wav.absolutePath, maxTokens)
            text = stripAsrOutput(raw)
            wav.delete()
        }

        val result = AsrResult(
            text = text,
            audioSeconds = audioSeconds,
            decodeMs = ms,
        )
        partialListener?.onPartial(result)
        return result
    }

    @Synchronized
    override fun release() {
        runCatching { QwenAsrNative.nativeRelease() }
        runtimeKey = null
    }

    private fun missingFiles(language: String): List<String> {
        val dir = modelDir(language)
        return requiredFiles.filterNot { File(dir, it).isFile }
    }

    private fun mapAsrLanguage(uiLanguage: String, params: AsrParams): String {
        val hint = params.string("language", "auto")
        return when (hint) {
            "Chinese", "zh" -> "Chinese"
            "English", "en" -> "English"
            "auto" -> if (uiLanguage == "en") "English" else "Chinese"
            else -> hint
        }
    }

    private fun stripAsrOutput(raw: String): String {
        var text = raw.trim()
        val tag = "<asr_text>"
        val idx = text.indexOf(tag)
        if (idx >= 0) {
            text = text.substring(idx + tag.length)
        }
        return text.trim()
    }
}
