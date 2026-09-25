pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.k2-fsa.sherpa-onnx") }
        }
    }
}

rootProject.name = "LocalAsr"
include(":app")
include(":asr-core")
include(":asr-whisper")
include(":asr-paraformer")
include(":asr-sensevoice")
include(":asr-parakeet")
include(":asr-streaming")
include(":asr-qwenasr")
