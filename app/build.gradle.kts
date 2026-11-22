import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Zugang zum Wegwerf-Sync-Server; steht nur in local.properties, nie im Repo.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "de.mcdax.ankiwear"
    // Die aktuellen AndroidX-Bibliotheken verlangen mindestens 37.1.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "de.mcdax.ankiwear"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-poc"
        // Galaxy Watch Ultra 2 läuft mit 32-Bit-Userspace (armeabi-v7a); x86_64 für den Emulator.
        ndk { abiFilters += listOf("armeabi-v7a", "x86_64") }
        buildConfigField("String", "SYNC_ENDPOINT", "\"${local.getProperty("sync.endpoint", "")}\"")
        buildConfigField("String", "SYNC_USER", "\"${local.getProperty("sync.user", "")}\"")
        buildConfigField("String", "SYNC_PASSWORD", "\"${local.getProperty("sync.password", "")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("io.github.david-allison:anki-backend-android:0.1.77-anki26.09.4")
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.wear.compose:compose-material3:1.7.2")
    implementation("androidx.wear.compose:compose-foundation:1.7.2")
    implementation("androidx.wear.compose:compose-navigation:1.7.2")
    implementation("com.caverock:androidsvg-aar:1.4")
}
