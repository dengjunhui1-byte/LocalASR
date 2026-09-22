package com.djh.localasr.core

import java.io.File

/** Best-effort lookup for sherpa-onnx export layouts after fetch_models flattens archives. */
object SherpaPaths {
    fun firstExisting(dir: File, vararg names: String): File? =
        names.firstNotNullOfOrNull { name ->
            File(dir, name).takeIf { it.isFile }
        }

    fun findBySuffix(dir: File, suffix: String): File? =
        dir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(suffix) }

    fun whisperEncoder(dir: File): File =
        firstExisting(dir, "base-encoder.int8.onnx", "small-encoder.int8.onnx", "tiny-encoder.int8.onnx")
            ?: findBySuffix(dir, "encoder.int8.onnx")
            ?: error("未找到 Whisper encoder ONNX：${dir.absolutePath}")

    fun whisperDecoder(dir: File): File =
        firstExisting(dir, "base-decoder.int8.onnx", "small-decoder.int8.onnx", "tiny-decoder.int8.onnx")
            ?: findBySuffix(dir, "decoder.int8.onnx")
            ?: error("未找到 Whisper decoder ONNX：${dir.absolutePath}")

    fun whisperTokens(dir: File): File =
        firstExisting(dir, "base-tokens.txt", "small-tokens.txt", "tiny-tokens.txt", "tokens.txt")
            ?: error("未找到 Whisper tokens：${dir.absolutePath}")

    fun paraformerModel(dir: File): File =
        firstExisting(dir, "model.int8.onnx", "model.onnx")
            ?: error("未找到 Paraformer 模型：${dir.absolutePath}")

    fun senseVoiceModel(dir: File): File =
        firstExisting(dir, "model.int8.onnx", "model.onnx")
            ?: error("未找到 SenseVoice 模型：${dir.absolutePath}")

    fun tokens(dir: File): File =
        firstExisting(dir, "tokens.txt") ?: error("未找到 tokens.txt：${dir.absolutePath}")

    fun streamingTransducer(dir: File): Triple<File, File, File> {
        val encoder = dir.listFiles()?.firstOrNull { it.name.contains("encoder") && it.name.endsWith(".onnx") }
            ?: error("未找到 streaming encoder：${dir.absolutePath}")
        val decoder = dir.listFiles()?.firstOrNull { it.name.contains("decoder") && it.name.endsWith(".onnx") }
            ?: error("未找到 streaming decoder：${dir.absolutePath}")
        val joiner = dir.listFiles()?.firstOrNull { it.name.contains("joiner") && it.name.endsWith(".onnx") }
            ?: error("未找到 streaming joiner：${dir.absolutePath}")
        return Triple(encoder, decoder, joiner)
    }
}
