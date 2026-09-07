pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TriChat"
include(":app")

val llamaDir = file("third_party/llama.cpp")
val pinnedLlama = "dbeb37548e25abc6e54961c4c99e63f191367809"
if (!llamaDir.exists()) {
    llamaDir.parentFile.mkdirs()
    fun run(vararg args: String) {
        val p = ProcessBuilder(*args).directory(rootDir).inheritIO().start()
        check(p.waitFor() == 0) { "Command failed: ${args.joinToString(" ")}" }
    }
    run("git", "init", llamaDir.absolutePath)
    run("git", "-C", llamaDir.absolutePath, "remote", "add", "origin", "https://github.com/ggml-org/llama.cpp.git")
    run("git", "-C", llamaDir.absolutePath, "fetch", "--depth", "1", "origin", pinnedLlama)
    run("git", "-C", llamaDir.absolutePath, "checkout", "FETCH_HEAD")
}

// TriChat targets modern arm64 Android phones only.
val llamaAndroidBuild = file("third_party/llama.cpp/examples/llama.android/lib/build.gradle.kts")
if (llamaAndroidBuild.exists()) {
    val original = llamaAndroidBuild.readText()
    val patched = original.replace(
        "abiFilters += listOf(\"arm64-v8a\", \"x86_64\")",
        "abiFilters += listOf(\"arm64-v8a\")"
    )
    if (patched != original) llamaAndroidBuild.writeText(patched)
}

// Two models run in separate processes. 4K context keeps KV-cache pressure lower
// and is a better fit for TriChat's short, speed-first meeting turns.
val aiChatCpp = file("third_party/llama.cpp/examples/llama.android/lib/src/main/cpp/ai_chat.cpp")
if (aiChatCpp.exists()) {
    val original = aiChatCpp.readText()
    val patched = original.replace(
        "constexpr int   DEFAULT_CONTEXT_SIZE    = 8192;",
        "constexpr int   DEFAULT_CONTEXT_SIZE    = 4096;"
    )
    if (patched != original) aiChatCpp.writeText(patched)
}

include(":llama-android-lib")
project(":llama-android-lib").projectDir = file("third_party/llama.cpp/examples/llama.android/lib")
