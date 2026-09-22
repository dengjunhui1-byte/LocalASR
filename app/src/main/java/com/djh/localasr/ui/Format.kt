package com.djh.localasr.ui

import java.util.Locale

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

fun languageLabel(code: String): String = when (code) {
    "zh" -> "中文"
    "en" -> "English"
    else -> code
}
