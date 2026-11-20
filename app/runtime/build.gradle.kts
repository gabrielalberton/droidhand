// EXPERIMENTAL module: a minimal Linux runtime (Alpine + proot) with a terminal and
// install recipes (Claude Code, Codex, T3). Separate APK and UID, no dependency on
// or from :portal. To remove: delete app/runtime and its line in settings.gradle.kts.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "work.alberton.droidhand.runtime"
    compileSdk = 35

    defaultConfig {
        applicationId = "work.alberton.droidhand.runtime"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-exp"
        ndk { abiFilters += "arm64-v8a" }
    }
    // proot and friends must be extracted to nativeLibraryDir to be executable.
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Fail early with a clear message when the proot binaries were not fetched.
tasks.named("preBuild") {
    doFirst {
        if (!file("src/main/jniLibs/arm64-v8a/libproot.so").exists())
            throw GradleException("Run app/runtime/scripts/fetch-proot.sh first (fetches proot from the Termux repo).")
    }
}

dependencies {
    // Termux's terminal libraries (Apache-2.0), community maintained.
    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.3")
    implementation("androidx.core:core-ktx:1.15.0")
}
