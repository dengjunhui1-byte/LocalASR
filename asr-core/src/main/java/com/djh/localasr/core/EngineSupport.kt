package com.djh.localasr.core

import android.content.Context
import android.os.Build
import java.io.File

abstract class BaseAsrEngine(
    protected val context: Context,
    override val descriptor: EngineDescriptor,
) : AsrEngine {

    protected fun requireSupportedAbi() {
        val required = descriptor.supportedAbis
        if (required.isEmpty()) return
        val available = Build.SUPPORTED_ABIS.toSet()
        if (required.none { it in available }) {
            throw UnsupportedDeviceException(
                "${descriptor.displayName} 需要 ${required.joinToString("/")} 架构，" +
                    "本机为 ${Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"}"
            )
        }
    }

    protected fun specFor(language: String): ModelSpec =
        descriptor.modelFor(language)
            ?: throw ModelNotInstalledException(
                descriptor.id,
                "${descriptor.displayName} 没有 $language 对应的模型",
            )

    protected fun modelDir(language: String): File {
        val spec = specFor(language)
        if (!ModelInstaller.isInstalled(context, descriptor.id, spec)) {
            throw ModelNotInstalledException(
                descriptor.id,
                "${spec.label} 尚未安装，请先在设置页完成导入",
            )
        }
        return ModelInstaller.modelDir(context, descriptor.id, spec)
    }

    override fun isInstalled(context: Context, language: String): Boolean {
        val spec = descriptor.modelFor(language) ?: return false
        return ModelInstaller.isInstalled(context, descriptor.id, spec)
    }
}
