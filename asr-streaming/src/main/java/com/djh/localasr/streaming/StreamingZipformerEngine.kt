package com.djh.localasr.streaming

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
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.system.measureTimeMillis

const val STREAMING_ENGINE_ID = "streaming"

object StreamingProvider : AsrEngineProvider {
    override val engineId: String = STREAMING_ENGINE_ID
    override fun create(context: Context): AsrEngine =
        StreamingZipformerEngine(context, EngineDescriptor.load(context, STREAMING_ENGINE_ID))
}

/** Online Zipformer — the only engine here that exposes true live partial results. */
class StreamingZipformerEngine(
    context: Context,
    descriptor: EngineDescriptor,
) : BaseAsrEngine(context, descriptor) {

    override val supportsLivePartial: Boolean = true

    private var signature: String? = null
    private var recognizer: OnlineRecognizer? = null
    private var liveStream: com.k2fsa.sherpa.onnx.OnlineStream? = null
    private var liveListener: AsrPartialListener? = null
    private var liveStartMs: Long = 0L

    override fun transcribeSamples(
        samples: FloatArray,
        sampleRate: Int,
        language: String,
        params: AsrParams,
        partialListener: AsrPartialListener?,
    ): AsrResult {
        // Offline-style batch decode using the streaming model: reset stream per utterance.
        beginLiveSession(language, params, partialListener ?: AsrPartialListener { true })
        feedLiveAudio(samples, sampleRate)
        return endLiveSession()
    }

    @Synchronized
    override fun beginLiveSession(language: String, params: AsrParams, listener: AsrPartialListener) {
        requireSupportedAbi()
        val rec = obtain(language, params)
        liveStream = rec.createStream()
        liveListener = listener
        liveStartMs = System.currentTimeMillis()
    }

    @Synchronized
    override fun feedLiveAudio(samples: FloatArray, sampleRate: Int) {
        val stream = liveStream ?: return
        val rec = recognizer ?: return
        val pcm = AudioIo.prepareForAsr(samples, sampleRate)
        val chunk = 1600 // 100 ms @ 16 kHz
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + chunk, pcm.size)
            stream.acceptWaveform(pcm.copyOfRange(offset, end), AudioIo.TARGET_SAMPLE_RATE)
            while (rec.isReady(stream)) {
                rec.decode(stream)
            }
            val text = rec.getResult(stream).text
            liveListener?.onPartial(
                AsrResult(text = text.trim(), partial = true)
            )
            offset = end
        }
    }

    @Synchronized
    override fun endLiveSession(): AsrResult {
        val stream = liveStream ?: return AsrResult("")
        val rec = recognizer ?: return AsrResult("")
        stream.inputFinished()
        while (rec.isReady(stream)) {
            rec.decode(stream)
        }
        val r = rec.getResult(stream)
        val ms = System.currentTimeMillis() - liveStartMs
        liveStream = null
        liveListener = null
        return AsrResult(
            text = r.text.trim(),
            partial = false,
            timestamps = r.timestamps.copyOf(),
            tokens = r.tokens.toList(),
            decodeMs = ms,
        )
    }

    @Synchronized
    private fun obtain(language: String, params: AsrParams): OnlineRecognizer {
        val dir = modelDir(language)
        val threads = params.int("numThreads", 2).coerceIn(1, 8)
        val decoding = params.string("decodingMethod", "greedy_search")
        val key = "$dir|$threads|$decoding"
        recognizer?.let { if (signature == key) return it }
        release()

        val (enc, dec, join) = SherpaPaths.streamingTransducer(dir)
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = enc.absolutePath,
                decoder = dec.absolutePath,
                joiner = join.absolutePath,
            ),
            tokens = SherpaPaths.tokens(dir).absolutePath,
            numThreads = threads,
            provider = "cpu",
            modelType = "zipformer",
        )
        val config = OnlineRecognizerConfig(
            modelConfig = modelConfig,
            decodingMethod = decoding,
            enableEndpoint = true,
        )
        return OnlineRecognizer(assetManager = null, config = config).also {
            recognizer = it
            signature = key
        }
    }

    @Synchronized
    override fun release() {
        liveStream = null
        liveListener = null
        recognizer?.release()
        recognizer = null
        signature = null
    }
}
