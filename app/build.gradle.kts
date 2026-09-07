plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.ikegami99.trichat"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "com.ikegami99.trichat"
        minSdk = 33
        targetSdk = 36
        versionCode = 6
        versionName = "0.1.5"
    }

    buildFeatures {
        buildConfig = true
    }

    // llama.cpp's Android CPU dispatcher scans applicationInfo.nativeLibraryDir
    // for libggml-cpu-*.so variants, so these must be extracted on install.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("TRICHAT_KEYSTORE_PATH")
            if (!ks.isNullOrBlank()) {
                storeFile = file(ks)
                storePassword = System.getenv("TRICHAT_KEYSTORE_PASSWORD") ?: "trichatlocal"
                keyAlias = System.getenv("TRICHAT_KEY_ALIAS") ?: "trichat"
                keyPassword = System.getenv("TRICHAT_KEY_PASSWORD") ?: "trichatlocal"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Qwen3.5 stays on llama.cpp/GGUF.
    implementation(project(":llama-android-lib"))

    // Gemma 4 E2B uses Google's Android-first LiteRT-LM runtime.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
}
