package com.djh.localasr.core

import android.content.Context

/** One finalized or partial decode from an engine. */
data class AsrResult(
    val text: String,
    val partial: Boolean = false,
    /** Word or token timestamps in seconds; empty when unsupported. */
    val timestamps: FloatArray = floatArrayOf(),
    val tokens: List<String> = emptyList(),
    /** SenseVoice extras; blank for other engines. */
    val detectedLanguage: String = "",
    val emotion: String = "",
    val audioEvent: String = "",
    val audioSeconds: Float = 0f,
    val decodeMs: Long = 0L,
) {
    val realTimeFactor: Float
        get() = if (audioSeconds > 0f) decodeMs / 1000f / audioSeconds else 0f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AsrResult) return false
        return text == other.text &&
            partial == other.partial &&
            timestamps.contentEquals(other.timestamps)
    }

    override fun hashCode(): Int = 31 * text.hashCode() + partial.hashCode()
}

/** Resolved parameter values keyed by [ParamSpec.key]. */
class AsrParams(private val values: Map<String, String>) {
    fun float(key: String, fallback: Float = 0f): Float =
        values[key]?.toFloatOrNull() ?: fallback

    fun int(key: String, fallback: Int = 0): Int =
        values[key]?.toDoubleOrNull()?.toInt() ?: fallback

    fun string(key: String, fallback: String = ""): String = values[key] ?: fallback

    fun bool(key: String, fallback: Boolean = false): Boolean =
        values[key]?.toBooleanStrictOrNull() ?: fallback

    companion object {
        fun defaults(descriptor: EngineDescriptor): AsrParams =
            AsrParams(descriptor.params.associate { it.key to it.default })

        fun of(values: Map<String, String>): AsrParams = AsrParams(values)
    }
}

class ModelNotInstalledException(val engineId: String, message: String) : Exception(message)

class UnsupportedDeviceException(message: String) : Exception(message)

fun interface AsrPartialListener {
    /** Return false to cancel decoding. */
    fun onPartial(result: AsrResult): Boolean
}

interface AsrEngine {
    val descriptor: EngineDescriptor

    /** True when live mic partial results are supported while recording. */
    val supportsLivePartial: Boolean get() = false

    fun isInstalled(context: Context, language: String): Boolean

    /**
     * Decodes mono PCM in [-1, 1]. File-based engines resample externally; sherpa expects 16 kHz
     * for most bundled models.
     */
    fun transcribeSamples(
        samples: FloatArray,
        sampleRate: Int,
        language: String,
        params: AsrParams,
        partialListener: AsrPartialListener? = null,
    ): AsrResult

    /** Optional streaming session for engines that expose sherpa OnlineRecognizer. */
    fun beginLiveSession(language: String, params: AsrParams, listener: AsrPartialListener) {
        throw UnsupportedOperationException("${descriptor.displayName} 不支持流式识别")
    }

    fun feedLiveAudio(samples: FloatArray, sampleRate: Int) {
        throw UnsupportedOperationException("${descriptor.displayName} 不支持流式识别")
    }

    fun endLiveSession(): AsrResult {
        throw UnsupportedOperationException("${descriptor.displayName} 不支持流式识别")
    }

    fun release()
}

interface AsrEngineProvider {
    val engineId: String
    fun create(context: Context): AsrEngine
}
