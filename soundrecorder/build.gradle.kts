plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.android.soundrecorder"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.android.soundrecorder"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "3.0"
        testInstrumentationRunner = "com.android.soundrecorder.RecordingSmoke"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { disable += "MissingTranslation" }
}

dependencies {
    implementation("androidx.core:core:1.13.0")
}
