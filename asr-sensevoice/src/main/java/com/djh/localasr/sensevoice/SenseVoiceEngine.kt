package com.djh.localasr.sensevoice

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
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlin.system.measureTimeMillis

const val SENSEVOICE_ENGINE_ID = "sensevoice"

object SenseVoiceProvider : AsrEngineProvider {
    override val engineId: String = SENSEVOICE_ENGINE_ID
    override fun create(context: Context): AsrEngine =
        SenseVoiceEngine(context, EngineDescriptor.load(context, SENSEVOICE_ENGINE_ID))
}

/**
 * Flagship ~234M-parameter engine. Official SenseVoiceSmall cards list 234M weights; this sits
 * just under the 0.3B line but is the strongest credible offline mid-size option with sherpa-onnx
 * on Android today. Whisper-small (~244M) is the closest English-centric alternative.
 */
class SenseVoiceEngine(
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
                detectedLanguage = r.lang,
                emotion = r.emotion,
                audioEvent = r.event,
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
        val lang = params.string("language", "auto").let {
            if (it != "auto") it else if (language == "zh") "zh" else if (language == "en") "en" else "auto"
        }
        val itn = params.bool("useItn", true)
        val key = "$dir|$threads|$lang|$itn"
        recognizer?.let { if (signature == key) return it }
        release()

        val modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = SherpaPaths.senseVoiceModel(dir).absolutePath,
                language = lang,
                useInverseTextNormalization = itn,
            ),
            tokens = SherpaPaths.tokens(dir).absolutePath,
            numThreads = threads,
            provider = "cpu",
            modelType = "sense_voice",
        )
        val config = OfflineRecognizerConfig(modelConfig = modelConfig)
        return OfflineRecognizer(assetManager = null, config = config).also {
            recognizer = it
            signature = key
        }
    }

    @Synchronized
    override fun release() {
        recognizer?.release()
        recognizer = null
        signature = null
    }
}
