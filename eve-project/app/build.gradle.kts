import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eve.robot"
    compileSdk = 35

    // Keep ONNX Runtime, tokenizer and GGUF files directly readable from app assets.
    androidResources {
        noCompress += listOf("ort", "bin", "tflite", "gguf")
    }

    // Include legacy JAR (v1) signing as well as modern APK signing so OEM installers
    // have the broadest possible compatibility. Keep the debug build signed with the
    // standard debug keystore used by this workflow.
    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.eve.robot"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }

    buildTypes {
        getByName("release") {
            // Redmi 12 is an ARM64 device. Keep every app feature/model, but do not package
            // duplicate x86/x86_64/32-bit native libraries in the phone-install APK.
            // The debug build remains universal so x86 emulator CI tests are unchanged.
            ndk {
                abiFilters += "arm64-v8a"
            }
            // Do not shrink code or resources: this package-size optimization is ABI-only.
            isMinifyEnabled = false
            isShrinkResources = false
            // Sign consistently with the project's verified CI APK for easy direct install.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // Real autoregressive GGUF inference with llama.cpp (CPU/NEON, arm64-v8a, API 24+).
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mlkit:image-labeling:17.0.7")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    // Compact Arabic Tiny Streaming ASR; model files are bundled and loaded with modelsFrom().
    implementation("ai.moonshine:moonshine-voice:0.1.5")
    // Offline hand-sign recognition: model is pinned/downloaded during the build and packaged locally.
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}
