// Detection lab: a throwaway APK with accessibility services of different "weight", to find out
// which trait a banking app reacts to. Not part of a release (scripts/release.sh builds :app only).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "work.alberton.droidhandlab"
    compileSdk = 35
    defaultConfig {
        applicationId = "work.alberton.droidhandlab"
        minSdk = 30
        targetSdk = 35
        versionName = "0.1"
        versionCode = 1
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
