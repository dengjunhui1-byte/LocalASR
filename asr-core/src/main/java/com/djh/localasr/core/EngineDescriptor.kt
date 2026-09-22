package com.djh.localasr.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ModelSource { ASSET, REMOTE }

enum class ModelArchive { NONE, ZIP, TAR_BZ2 }

enum class ParamType { FLOAT, INT, ENUM, BOOL }

data class ParamSpec(
    val key: String,
    val type: ParamType,
    val label: String,
    val default: String,
    val min: Double = 0.0,
    val max: Double = 1.0,
    val step: Double = 0.01,
    val values: List<String> = emptyList(),
    val help: String = "",
) {
    fun defaultFloat(): Float = default.toFloatOrNull() ?: 0f
    fun defaultInt(): Int = default.toDoubleOrNull()?.toInt() ?: 0
    fun defaultBool(): Boolean = default.toBooleanStrictOrNull() ?: false
}

data class RemoteModelFile(
    val path: String,
    val sizeBytes: Long = 0L,
    val urls: List<String> = emptyList(),
)

data class ModelSpec(
    val id: String,
    val label: String,
    val languages: List<String>,
    val dir: String,
    val source: ModelSource,
    val version: String,
    val sizeBytes: Long,
    val urls: List<String> = emptyList(),
    val archive: ModelArchive = ModelArchive.NONE,
    val sha256: String = "",
    val onDemand: Boolean = false,
    /** When non-empty, [ModelInstaller] fetches each path separately (MNN packs). */
    val files: List<RemoteModelFile> = emptyList(),
) {
    val installable: Boolean
        get() = source == ModelSource.ASSET ||
            urls.isNotEmpty() ||
            files.any { it.urls.isNotEmpty() }
}

data class EngineDescriptor(
    val id: String,
    val displayName: String,
    val runtime: String,
    val description: String,
    val languages: List<String>,
    val sampleHints: Map<String, String>,
    val models: List<ModelSpec>,
    val params: List<ParamSpec>,
    val supportedAbis: List<String> = emptyList(),
    val parameterCount: String = "",
    val flagship: Boolean = false,
) {
    fun modelFor(language: String): ModelSpec? =
        models.firstOrNull { language in it.languages }

    fun sampleHint(language: String): String = sampleHints[language].orEmpty()

    val assetRoot: String get() = "asr/$id"

    companion object {
        fun load(context: Context, engineId: String): EngineDescriptor {
            val json = context.assets.open("asr/$engineId/config.json").use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            return parse(JSONObject(json))
        }

        fun parse(root: JSONObject): EngineDescriptor = EngineDescriptor(
            id = root.getString("id"),
            displayName = root.getString("displayName"),
            runtime = root.optString("runtime"),
            description = root.optString("description"),
            languages = root.getJSONArray("languages").toStringList(),
            sampleHints = root.getJSONObject("sampleHints").toStringMap(),
            models = root.getJSONArray("models").map { it.toModelSpec() },
            params = root.getJSONArray("params").map { it.toParamSpec() },
            supportedAbis = root.optJSONArray("supportedAbis")?.toStringList() ?: emptyList(),
            parameterCount = root.optString("parameterCount"),
            flagship = root.optBoolean("flagship", false),
        )

        private fun JSONObject.toModelSpec() = ModelSpec(
            id = getString("id"),
            label = optString("label", getString("id")),
            languages = getJSONArray("languages").toStringList(),
            dir = getString("dir"),
            source = ModelSource.valueOf(optString("source", "asset").uppercase()),
            version = optString("version", "1"),
            sizeBytes = optLong("sizeBytes", 0L),
            urls = (optJSONArray("urls")?.toStringList() ?: listOfNotNull(
                optString("url").takeIf { it.isNotBlank() }
            )),
            archive = ModelArchive.valueOf(optString("archive", "none").uppercase()),
            sha256 = optString("sha256"),
            onDemand = optBoolean("onDemand", false),
            files = optJSONArray("files")?.map { it.toRemoteModelFile() } ?: emptyList(),
        )

        private fun JSONObject.toRemoteModelFile() = RemoteModelFile(
            path = getString("path"),
            sizeBytes = optLong("sizeBytes", 0L),
            urls = optJSONArray("urls")?.toStringList() ?: listOfNotNull(
                optString("url").takeIf { it.isNotBlank() }
            ),
        )

        private fun JSONObject.toParamSpec() = ParamSpec(
            key = getString("key"),
            type = ParamType.valueOf(getString("type").uppercase()),
            label = optString("label", getString("key")),
            default = jsonDefaultString(get("default")),
            min = optDouble("min", 0.0),
            max = optDouble("max", 1.0),
            step = optDouble("step", 0.01),
            values = optJSONArray("values")?.toStringList() ?: emptyList(),
            help = optString("help"),
        )

        private fun jsonDefaultString(raw: Any?): String = when (raw) {
            null -> ""
            is Boolean -> raw.toString()
            is Number -> {
                val n = raw.toDouble()
                if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()
            }
            else -> raw.toString()
        }

        private fun JSONArray.toStringList(): List<String> =
            (0 until length()).map { getString(it) }

        private fun JSONObject.toStringMap(): Map<String, String> =
            keys().asSequence().associateWith { getString(it) }

        private fun <T> JSONArray.map(transform: (JSONObject) -> T): List<T> =
            (0 until length()).map { transform(getJSONObject(it)) }
    }
}
