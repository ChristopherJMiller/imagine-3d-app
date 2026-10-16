plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.imagine3d.llama"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // -Pabis=arm64-v8a builds just the phone ABI (smaller APK, half the native build time).
            abiFilters += (project.findProperty("abis") as String?)?.split(",") ?: listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // llama.cpp is unusably slow unoptimised, so always build it in Release.
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_BUILD_MTMD=ON",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DLLAMA_SUBPROCESS=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_LLAMAFILE=OFF",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
