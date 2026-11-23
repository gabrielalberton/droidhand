import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release signing. Point DROIDHAND_KEYSTORE_PROPERTIES (or android/keystore.properties,
 * git-ignored) at a properties file with storeFile, storePassword, keyAlias, keyPassword.
 * Without it the release build is produced unsigned.
 */
val keystoreProps: Properties? = (System.getenv("DROIDHAND_KEYSTORE_PROPERTIES")?.let(::file)
    ?: rootProject.file("keystore.properties"))
    .takeIf { it.isFile }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "work.alberton.droidhand"
    compileSdk = 35

    defaultConfig {
        applicationId = "work.alberton.droidhand"
        minSdk = 30
        targetSdk = 35
        // Single source of truth for the app version (keep in sync with mcp-server/package.json).
        versionName = "1.2.2"
        versionCode = 10202 // major * 10000 + minor * 100 + patch
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; aidl = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

base { archivesName.set("droidhand") }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Invites: QR code drawn offline (ZXing core) and read with Google's code scanner
    // (Play services UI, so the app needs no camera permission).
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
