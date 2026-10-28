plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.russianstep"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.russianstep"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
