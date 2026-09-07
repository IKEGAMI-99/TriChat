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
val pinnedLlama = "73ab7599b553c03f6f5d2db24a18ad76f2eb36a3"
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

// TriChat is distributed for modern Android phones, so avoid spending CI time
// compiling an unused x86_64 native backend from the upstream Android sample.
val llamaAndroidBuild = file("third_party/llama.cpp/examples/llama.android/lib/build.gradle.kts")
if (llamaAndroidBuild.exists()) {
    val original = llamaAndroidBuild.readText()
    val patched = original.replace(
        "abiFilters += listOf(\"arm64-v8a\", \"x86_64\")",
        "abiFilters += listOf(\"arm64-v8a\")"
    )
    if (patched != original) llamaAndroidBuild.writeText(patched)
}

include(":llama-android-lib")
project(":llama-android-lib").projectDir = file("third_party/llama.cpp/examples/llama.android/lib")
