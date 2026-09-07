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
        versionCode = 4
        versionName = "0.1.3"
    }

    buildFeatures {
        buildConfig = true
    }

    // llama.cpp's Android CPU dispatcher scans applicationInfo.nativeLibraryDir
    // for libggml-cpu-*.so variants. Modern AGP normally leaves native libs
    // mmap-able inside the APK instead of extracting them to that directory,
    // which makes backend discovery silently fail and every GGUF load return null.
    // Force real on-disk extraction so ggml_backend_load_all_from_path() can see them.
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":llama-android-lib"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
}
