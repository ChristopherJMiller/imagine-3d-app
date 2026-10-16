plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.imagine3d"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.imagine3d"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-spike"
        ndk {
            // -Pabis=arm64-v8a builds just the phone ABI (smaller APK, half the native build time).
            abiFilters += (project.findProperty("abis") as String?)?.split(",") ?: listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Spike: sign release with the debug key so it can be sideloaded.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // The OpenSCAD wasm is fetched by the WebView; keep it uncompressed in the APK.
        noCompress += listOf("wasm")
    }

    packaging {
        jniLibs {
            // ggml loads its CPU-variant backends by scanning nativeLibraryDir, so the
            // libraries must be extracted to disk.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":llama"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.arcore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
