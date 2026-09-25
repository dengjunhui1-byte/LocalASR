package com.djh.localasr.qwenasr

/**
 * JNI surface for libqwenasr_jni.so (MNN master + LLM + audio, statically linked).
 * Build with [tools/build_qwenasr_native.sh]; symbols are hidden except these natives.
 */
internal object QwenAsrNative {
    private var loadError: UnsatisfiedLinkError? = null

    init {
        runCatching { System.loadLibrary("qwenasr_jni") }
            .onFailure { loadError = it as? UnsatisfiedLinkError ?: UnsatisfiedLinkError(it.message) }
    }

    fun requireLoaded() {
        loadError?.let {
            throw IllegalStateException(
                "libqwenasr_jni.so 未找到。请在 arm64 设备上运行 tools/build_qwenasr_native.sh " +
                    "并将产物复制到 asr-qwenasr/src/main/jniLibs/arm64-v8a/",
                it,
            )
        }
    }

    external fun nativeEnsureLoaded(
        configPath: String,
        tmpPath: String,
        threads: Int,
        asrLanguage: String,
    )

    external fun nativeRecognize(
        wavPath: String,
        maxNewTokens: Int,
    ): String

    external fun nativeRelease()
}
