package com.djh.localasr.paraformer

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
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlin.system.measureTimeMillis

const val PARAFORMER_ENGINE_ID = "paraformer"

object ParaformerProvider : AsrEngineProvider {
    override val engineId: String = PARAFORMER_ENGINE_ID
    override fun create(context: Context): AsrEngine =
        ParaformerEngine(context, EngineDescriptor.load(context, PARAFORMER_ENGINE_ID))
}

/** Non-autoregressive Paraformer — the fast Chinese baseline in this app. */
class ParaformerEngine(
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
        val key = "$dir|$threads"
        recognizer?.let { if (signature == key) return it }
        release()

        val modelConfig = OfflineModelConfig(
            paraformer = OfflineParaformerModelConfig(
                model = SherpaPaths.paraformerModel(dir).absolutePath,
            ),
            tokens = SherpaPaths.tokens(dir).absolutePath,
            numThreads = threads,
            provider = "cpu",
            modelType = "paraformer",
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
