import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val useRev30Ort = providers.gradleProperty("supertonicOrtRev30").orNull == "true"
val useRev40CpuRef = providers.gradleProperty("supertonicOrtRev40CpuRef").orNull == "true"
require(!(useRev30Ort && useRev40CpuRef)) { "Choose only one ORT diagnostic runtime" }

android {
    namespace = "com.supertonic.tts"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.supertonic.tts"
        minSdk = 26
        targetSdk = 34
        versionCode = 52
        versionName = "0.1.44"
        buildConfigField("boolean", "ORT_XNNPACK_AVAILABLE", (!(useRev30Ort || useRev40CpuRef)).toString())
        buildConfigField(
            "String",
            "ORT_RUNTIME_VARIANT",
            if (useRev40CpuRef) "\"REV40_HTA_CPU_REF\"" else if (useRev30Ort) "\"REV30_QNN_ONLY\"" else "\"REV32_QNN_XNNPACK\"",
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libQnnHtpV81Skel.so")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":sdk"))

    // Native LiteRT CPU remains in :sdk. Qualcomm acceleration uses the pinned
    // QAIRT runtime; the official qnn-litert-delegate AAR supplies the new
    // Multi-P FP32 T64/L64 selected-signature preview.
    implementation(files("libs/qnn-runtime-2.47.0.aar"))
    implementation(files("libs/qnn-litert-delegate-2.47.0.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
