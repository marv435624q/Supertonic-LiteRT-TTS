import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val useRev30Ort = providers.gradleProperty("supertonicOrtRev30").orNull == "true"
val useRev40CpuRef = providers.gradleProperty("supertonicOrtRev40CpuRef").orNull == "true"
require(!(useRev30Ort && useRev40CpuRef)) { "Choose only one ORT diagnostic runtime" }


val speechCoreDir = providers.gradleProperty("SPEECH_CORE_DIR")
    .orElse("${project.rootDir}/speech-core")
    .get()

android {
    namespace = "audio.soniqo.speech"
    compileSdk = 35
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        buildConfigField("boolean", "ORT_XNNPACK_AVAILABLE", (!(useRev30Ort || useRev40CpuRef)).toString())
        buildConfigField(
            "String",
            "ORT_RUNTIME_VARIANT",
            if (useRev40CpuRef) "\"REV40_HTA_CPU_REF\"" else if (useRev30Ort) "\"REV30_QNN_ONLY\"" else "\"REV32_QNN_XNNPACK\"",
        )
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DSPEECH_CORE_DIR=$speechCoreDir",
                    "-DLITERT_DIR=${project.rootDir}/litert",
                )
                // The Android app only loads libspeech_android.so. Explicitly
                // restrict AGP to that target so EXCLUDE_FROM_ALL benchmark
                // executables are not compiled/linked during every APK build.
                targets += listOf("speech_android")
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles("consumer-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Keep native libraries extracted as real files. ORT QNN backend_path and
    // the SM6350 HTA runtime resolve libraries from nativeLibraryDir.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.annotation:annotation:1.8.2")

    // Custom ORT 1.28.0 Android AAR. Its Java API/JNI shell comes from the
    // already validated REV30 QNN/HTA AAR, while libonnxruntime.so is rebuilt
    // from the exact upstream v1.28.0 tag with BOTH providers compiled in:
    //   --use_qnn static_lib  +  --use_xnnpack
    // and the existing QNN HTA backend-recognition patch applied. The app
    // continues to provide the pinned qnn-runtime-2.44.0.aar separately.
    implementation(
        files(
            if (useRev40CpuRef) "libs/onnxruntime-android-qnn-1.28.0-hta-rev40-cpuref.aar"
            else if (useRev30Ort) "libs/onnxruntime-android-qnn-1.28.0-hta.aar"
            else "libs/onnxruntime-android-qnn-xnnpack-1.28.0-hta.aar"
        )
    )

}
