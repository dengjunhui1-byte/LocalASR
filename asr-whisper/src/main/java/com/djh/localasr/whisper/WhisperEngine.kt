package com.djh.localasr.whisper

import android.content.Context
import com.djh.localasr.core.AsrEngine
import com.djh.localasr.core.AsrEngineProvider
import com.djh.localasr.core.AsrParams
import com.djh.localasr.core.AsrPartialListener
import com.djh.localasr.core.AsrResult
import com.djh.localasr.core.AudioIo
import com.djh.localasr.core.BaseAsrEngine
import com.djh.localasr.core.EngineDescriptor
import com.djh.localasr.core.SherpaPaths
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlin.system.measureTimeMillis

const val WHISPER_ENGINE_ID = "whisper"

object WhisperProvider : AsrEngineProvider {
    override val engineId: String = WHISPER_ENGINE_ID
    override fun create(context: Context): AsrEngine =
        WhisperEngine(context, EngineDescriptor.load(context, WHISPER_ENGINE_ID))
}

/**
 * Whisper through sherpa-onnx offline recognizer. This is the "classic autoregressive" baseline
 * in LocalASR: heavier than Paraformer/SenseVoice but familiar to anyone who has used OpenAI Whisper.
 */
class WhisperEngine(
    context: Context,
    descriptor: EngineDescriptor,
) : BaseAsrEngine(context, descriptor) {

    private var signature: String? = null
    private var recognizer: OfflineRecognizer? = null

    override fun transcribeSamples(
        samples: FloatArray,
        sampleRate: Int,
        language: String,
        params: AsrParams,
        partialListener: AsrPartialListener?,
    ): AsrResult {
        requireSupportedAbi()
        val pcm = AudioIo.prepareForAsr(samples, sampleRate)
        val audioSeconds = pcm.size.toFloat() / AudioIo.TARGET_SAMPLE_RATE
        val rec = obtain(language, params)
        lateinit var out: AsrResult
        val ms = measureTimeMillis {
            val stream = rec.createStream()
            stream.acceptWaveform(pcm, AudioIo.TARGET_SAMPLE_RATE)
            rec.decode(stream)
            val r = rec.getResult(stream)
            out = AsrResult(
                text = r.text.trim(),
                timestamps = r.timestamps.copyOf(),
                tokens = r.tokens.toList(),
                audioSeconds = audioSeconds,
            )
            partialListener?.onPartial(out)
        }
        return out.copy(decodeMs = ms)
    }

    @Synchronized
    private fun obtain(language: String, params: AsrParams): OfflineRecognizer {
        val dir = modelDir(language)
        val threads = params.int("numThreads", 2).coerceIn(1, 8)
        val whisperLang = mapWhisperLanguage(params.string("whisperLanguage", "auto"), language)
        val timestamps = params.bool("enableTokenTimestamps", false)
        val key = "$dir|$threads|$whisperLang|$timestamps"
        recognizer?.let { if (signature == key) return it }
        release()

        val modelConfig = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = SherpaPaths.whisperEncoder(dir).absolutePath,
                decoder = SherpaPaths.whisperDecoder(dir).absolutePath,
                language = whisperLang,
                task = "transcribe",
                enableTokenTimestamps = timestamps,
            ),
            tokens = SherpaPaths.whisperTokens(dir).absolutePath,
            numThreads = threads,
            provider = "cpu",
            modelType = "whisper",
        )
        val config = OfflineRecognizerConfig(modelConfig = modelConfig)
        return OfflineRecognizer(assetManager = null, config = config).also {
            recognizer = it
            signature = key
        }
    }

    private fun mapWhisperLanguage(param: String, uiLanguage: String): String = when (param) {
        "auto" -> if (uiLanguage == "zh") "zh" else if (uiLanguage == "en") "en" else "auto"
        "zh" -> "zh"
        "en" -> "en"
        else -> "auto"
    }

    @Synchronized
    override fun release() {
        recognizer?.release()
        recognizer = null
        signature = null
    }
}
