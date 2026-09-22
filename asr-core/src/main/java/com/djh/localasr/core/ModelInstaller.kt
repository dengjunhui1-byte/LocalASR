package com.djh.localasr.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

enum class InstallPhase { COPYING, DOWNLOADING, EXTRACTING, VERIFYING }

data class InstallProgress(
    val engineId: String,
    val modelLabel: String,
    val phase: InstallPhase,
    val currentFile: String,
    val bytesDone: Long,
    val bytesTotal: Long,
) {
    val fraction: Float
        get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f
}

/**
 * Materializes model files onto external storage under `localasr/models/<engineId>/<dir>/`.
 *
 * Same contract as LocalTTS: assets are namespaced as `asr/<engineId>/models/...` and unpacked
 * before first use because native runtimes read real paths, not APK entries.
 */
object ModelInstaller {

    private const val MARKER = ".installed"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun installRoot(context: Context): File =
        File(context.getExternalFilesDir(null), "localasr/models")

    fun modelDir(context: Context, engineId: String, spec: ModelSpec): File =
        File(installRoot(context), "$engineId/${spec.dir}")

    fun isInstalled(context: Context, engineId: String, spec: ModelSpec): Boolean {
        val marker = File(modelDir(context, engineId, spec), MARKER)
        return marker.isFile && marker.readText().trim() == spec.version
    }

    fun installedBytes(context: Context, engineId: String, spec: ModelSpec): Long =
        modelDir(context, engineId, spec).walkBottomUp()
            .filter { it.isFile }
            .sumOf { it.length() }

    fun remove(context: Context, engineId: String, spec: ModelSpec) {
        modelDir(context, engineId, spec).deleteRecursively()
    }

    fun pendingStartupInstalls(
        context: Context,
        descriptors: List<EngineDescriptor>,
    ): List<Pair<EngineDescriptor, ModelSpec>> = descriptors.flatMap { engine ->
        engine.models
            .filter { it.source == ModelSource.ASSET && !it.onDemand }
            .filterNot { isInstalled(context, engine.id, it) }
            .map { engine to it }
    }

    suspend fun install(
        context: Context,
        engineId: String,
        spec: ModelSpec,
        onProgress: (InstallProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val dest = modelDir(context, engineId, spec)
        dest.deleteRecursively()
        dest.mkdirs()

        when (spec.source) {
            ModelSource.ASSET -> copyFromAssets(context, engineId, spec, dest, onProgress)
            ModelSource.REMOTE -> downloadRemote(context, engineId, spec, dest, onProgress)
        }

        File(dest, MARKER).writeText(spec.version)
    }

    private suspend fun copyFromAssets(
        context: Context,
        engineId: String,
        spec: ModelSpec,
        dest: File,
        onProgress: (InstallProgress) -> Unit,
    ) {
        val assets = context.assets
        val assetRoot = "asr/$engineId/models/${spec.dir}"
        val files = mutableListOf<String>()
        collectAssetFiles(context, assetRoot, files)

        val total = if (spec.sizeBytes > 0) spec.sizeBytes else -1L
        var done = 0L
        for (relative in files) {
            coroutineContext.ensureActive()
            val target = File(dest, relative)
            target.parentFile?.mkdirs()
            assets.open("$assetRoot/$relative").use { input ->
                target.outputStream().use { output ->
                    done += input.pipeTo(output) { chunk ->
                        onProgress(
                            InstallProgress(
                                engineId, spec.label, InstallPhase.COPYING,
                                relative, done + chunk, if (total > 0) total else done + chunk,
                            )
                        )
                    }
                }
            }
        }
    }

    private fun collectAssetFiles(context: Context, path: String, into: MutableList<String>) {
        fun walk(current: String, prefix: String) {
            val children = context.assets.list(current).orEmpty()
            if (children.isEmpty()) {
                into += prefix
                return
            }
            for (child in children) {
                walk("$current/$child", if (prefix.isEmpty()) child else "$prefix/$child")
            }
        }
        for (child in context.assets.list(path).orEmpty()) {
            walk("$path/$child", child)
        }
    }

    private suspend fun downloadRemote(
        context: Context,
        engineId: String,
        spec: ModelSpec,
        dest: File,
        onProgress: (InstallProgress) -> Unit,
    ) {
        require(spec.urls.isNotEmpty()) { "model ${spec.id} is remote but has no url" }

        val cache = File(context.cacheDir, "model-downloads").apply { mkdirs() }
        val payload = File(cache, "${engineId}-${spec.id}.part")
        val name = spec.urls.first().substringAfterLast('/').substringBefore('?')

        val job = coroutineContext[kotlinx.coroutines.Job]
        val checkActive = { job?.ensureActive() ?: Unit }

        var lastFailure: Throwable? = null
        val downloaded = spec.urls.any { url ->
            runCatching { downloadTo(payload, url, spec, engineId, name, checkActive, onProgress) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    lastFailure = it
                }
                .isSuccess
        }
        if (!downloaded) throw lastFailure ?: IllegalStateException("下载失败：${spec.id}")

        if (spec.sha256.isNotBlank()) {
            onProgress(
                InstallProgress(
                    engineId, spec.label, InstallPhase.VERIFYING, name, 0, payload.length()
                )
            )
            val actual = payload.sha256()
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                payload.delete()
                error("checksum mismatch for ${spec.id}: expected ${spec.sha256}, got $actual")
            }
        }

        when (spec.archive) {
            ModelArchive.ZIP -> {
                unzip(payload, dest, spec, engineId, onProgress)
                payload.delete()
            }
            ModelArchive.TAR_BZ2 -> {
                untarBz2Flat(payload, dest, spec, engineId, onProgress)
                payload.delete()
            }
            ModelArchive.NONE -> {
                payload.copyTo(File(dest, name), overwrite = true)
                payload.delete()
            }
        }
    }

    private fun downloadTo(
        payload: File,
        url: String,
        spec: ModelSpec,
        engineId: String,
        name: String,
        checkActive: () -> Unit,
        onProgress: (InstallProgress) -> Unit,
    ) {
        var existing = if (payload.isFile) payload.length() else 0L
        if (spec.sizeBytes > 0 && existing >= spec.sizeBytes) {
            payload.delete()
            existing = 0L
        }

        val request = Request.Builder().url(url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("下载失败（HTTP ${response.code}）：$url")
            }
            val resumed = response.code == 206
            if (!resumed) existing = 0L

            val total = if (spec.sizeBytes > 0) {
                spec.sizeBytes
            } else {
                (response.body?.contentLength() ?: -1L).let { if (it > 0) it + existing else -1L }
            }

            var done = existing
            response.body!!.byteStream().use { input ->
                java.io.FileOutputStream(payload, resumed).use { output ->
                    done += input.pipeTo(output, checkActive = checkActive) { chunk ->
                        onProgress(
                            InstallProgress(
                                engineId, spec.label, InstallPhase.DOWNLOADING,
                                name, done + chunk, if (total > 0) total else done + chunk,
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun unzip(
        archive: File,
        dest: File,
        spec: ModelSpec,
        engineId: String,
        onProgress: (InstallProgress) -> Unit,
    ) {
        var done = 0L
        val total = archive.length()
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val target = File(dest, entry.name.substringAfterLast('/'))
                target.parentFile?.mkdirs()
                target.outputStream().use { output ->
                    done += zip.pipeTo(output, closeSource = false) { chunk ->
                        onProgress(
                            InstallProgress(
                                engineId, spec.label, InstallPhase.EXTRACTING,
                                target.name, done + chunk, total,
                            )
                        )
                    }
                }
                zip.closeEntry()
            }
        }
    }

    /** Flattens the usual single top-level directory in sherpa-onnx tarballs. */
    private suspend fun untarBz2Flat(
        archive: File,
        dest: File,
        spec: ModelSpec,
        engineId: String,
        onProgress: (InstallProgress) -> Unit,
    ) {
        val staging = File(dest.parentFile, "${dest.name}.staging").apply {
            deleteRecursively()
            mkdirs()
        }
        var done = 0L
        val total = archive.length()
        BZip2CompressorInputStream(archive.inputStream().buffered()).use { bz2 ->
            TarArchiveInputStream(bz2).use { tar ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = tar.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.trim('/')
                    if (name.isEmpty()) continue
                    val target = File(staging, name)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output ->
                        done += tar.pipeTo(output, closeSource = false) { chunk ->
                            onProgress(
                                InstallProgress(
                                    engineId, spec.label, InstallPhase.EXTRACTING,
                                    target.name, done + chunk, total,
                                )
                            )
                        }
                    }
                }
            }
        }
        val inner = staging.listFiles()?.singleOrNull { it.isDirectory } ?: staging
        dest.deleteRecursively()
        dest.mkdirs()
        inner.listFiles().orEmpty().forEach { child ->
            child.copyRecursively(File(dest, child.name), overwrite = true)
        }
        staging.deleteRecursively()
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private inline fun InputStream.pipeTo(
        output: java.io.OutputStream,
        closeSource: Boolean = true,
        checkActive: () -> Unit = {},
        onTick: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(1 shl 16)
        var moved = 0L
        var lastTick = 0L
        try {
            while (true) {
                checkActive()
                val read = read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                moved += read
                if (moved - lastTick > 256 shl 10) {
                    lastTick = moved
                    onTick(moved)
                }
            }
        } finally {
            if (closeSource) close()
        }
        onTick(moved)
        return moved
    }
}
