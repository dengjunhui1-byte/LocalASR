package com.djh.localasr.core

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

object AudioIo {
    const val TARGET_SAMPLE_RATE = 16_000

    fun readWavMono(path: String): Pair<FloatArray, Int> {
        val bytes = File(path).readBytes()
        if (bytes.size < 44 || !bytes.copyOfRange(0, 4).decodeToString().startsWith("RIFF")) {
            error("不是 PCM WAV：$path")
        }
        var pos = 12
        var sampleRate = 16_000
        var bits = 16
        var channels = 1
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = bytes.copyOfRange(pos, pos + 4).decodeToString()
            val size = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val chunkData = pos + 8
            if (id == "fmt " && chunkData + 16 <= bytes.size) {
                channels = ByteBuffer.wrap(bytes, chunkData + 2, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                sampleRate = ByteBuffer.wrap(bytes, chunkData + 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int
                bits = ByteBuffer.wrap(bytes, chunkData + 14, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            }
            if (id == "data") {
                dataStart = chunkData
                dataLen = size
                break
            }
            pos = chunkData + size + (size and 1)
        }
        require(dataStart >= 0) { "WAV 缺少 data 块：$path" }
        val pcm = bytes.copyOfRange(dataStart, min(dataStart + dataLen, bytes.size))
        val samples = pcmToFloat(pcm, bits, channels)
        val mono = if (channels == 1) samples else downmixToMono(samples, channels)
        return mono to sampleRate
    }

    fun resampleLinear(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return samples
        val outLen = (samples.size.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i.toDouble() * fromRate / toRate
            val idx = srcPos.toInt().coerceIn(0, samples.lastIndex)
            val frac = (srcPos - idx).toFloat()
            val next = samples[min(idx + 1, samples.lastIndex)]
            out[i] = samples[idx] * (1 - frac) + next * frac
        }
        return out
    }

    fun prepareForAsr(samples: FloatArray, sampleRate: Int): FloatArray {
        val mono = samples
        val at16k = resampleLinear(mono, sampleRate, TARGET_SAMPLE_RATE)
        return at16k
    }

    private fun pcmToFloat(pcm: ByteArray, bits: Int, channels: Int): FloatArray {
        require(bits == 16) { "仅支持 16-bit PCM WAV" }
        val count = pcm.size / 2
        val out = FloatArray(count)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            out[i] = bb.short / 32768f
        }
        return out
    }

    private fun downmixToMono(interleaved: FloatArray, channels: Int): FloatArray {
        val frames = interleaved.size / channels
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            mono[f] = sum / channels
        }
        return mono
    }
}

class MicCapture(
    private val sampleRate: Int = AudioIo.TARGET_SAMPLE_RATE,
) {
    private var record: AudioRecord? = null
    private val buffer = mutableListOf<Float>()

    val isRecording: Boolean get() = record?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    fun start(): Boolean {
        stop()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            return false
        }
        buffer.clear()
        record = ar
        ar.startRecording()
        return true
    }

    fun drain(): FloatArray {
        val ar = record ?: return floatArrayOf()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val chunk = ShortArray(minBuf)
        val read = ar.read(chunk, 0, chunk.size)
        if (read > 0) {
            for (i in 0 until read) buffer.add(chunk[i] / 32768f)
        }
        return buffer.toFloatArray()
    }

    fun stopAndTake(): FloatArray {
        val ar = record
        record = null
        if (ar != null) {
            try {
                if (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) ar.stop()
            } finally {
                ar.release()
            }
        }
        return buffer.toFloatArray().also { buffer.clear() }
    }

    fun stop() {
        stopAndTake()
    }
}

fun writeWav(path: File, samples: FloatArray, sampleRate: Int) {
    val pcm = ShortArray(samples.size)
    for (i in samples.indices) {
        pcm[i] = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
    }
    val dataSize = pcm.size * 2
    RandomAccessFile(path, "rw").use { raf ->
        raf.setLength(0)
        raf.write("RIFF".toByteArray())
        raf.write(intLe(36 + dataSize))
        raf.write("WAVE".toByteArray())
        raf.write("fmt ".toByteArray())
        raf.write(intLe(16))
        raf.write(shortLe(1))
        raf.write(shortLe(1))
        raf.write(intLe(sampleRate))
        raf.write(intLe(sampleRate * 2))
        raf.write(shortLe(2))
        raf.write(shortLe(16))
        raf.write("data".toByteArray())
        raf.write(intLe(dataSize))
        val bb = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) bb.putShort(s)
        raf.write(bb.array())
    }
}

private fun intLe(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
private fun shortLe(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
