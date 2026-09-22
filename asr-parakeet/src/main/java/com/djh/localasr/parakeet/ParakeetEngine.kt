package com.djh.localasr.parakeet

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
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlin.system.measureTimeMillis

const val PARAKEET_ENGINE_ID = "parakeet"

object ParakeetProvider : AsrEngineProvider {
    override val engineId: String = PARAKEET_ENGINE_ID
    override fun create(context: Context): AsrEngine =
        ParakeetEngine(context, EngineDescriptor.load(context, PARAKEET_ENGINE_ID))
}

/**
 * NVIDIA Parakeet-TDT-0.6B-v2 via sherpa-onnx NeMo transducer export.
 *
 * Official parameter count: 600 million (see nvidia/parakeet-tdt-0.6b-v2 on Hugging Face).
 * This is LocalASR's designated engine for the 0.3B–0.6B (300M–600M) parameter band.
 */
class ParakeetEngine(
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
        val decoding = params.string("decodingMethod", "greedy_search")
        val maxPaths = params.int("maxActivePaths", 4).coerceIn(1, 10)
        val key = "$dir|$threads|$decoding|$maxPaths"
        recognizer?.let { if (signature == key) return it }
        release()

        val (enc, dec, join) = SherpaPaths.streamingTransducer(dir)
        val modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = enc.absolutePath,
                decoder = dec.absolutePath,
                joiner = join.absolutePath,
            ),
            tokens = SherpaPaths.tokens(dir).absolutePath,
            numThreads = threads,
            provider = "cpu",
            modelType = "nemo_transducer",
        )
        val config = OfflineRecognizerConfig(
            modelConfig = modelConfig,
            decodingMethod = decoding,
            maxActivePaths = maxPaths,
        )
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
