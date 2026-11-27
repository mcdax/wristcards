import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Credentials for the throwaway sync server; only in local.properties, never in the repo.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "de.mcdax.wristcards"
    // The current AndroidX libraries require at least 37.1.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "de.mcdax.wristcards"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-poc"
        // Galaxy Watch Ultra 2 runs a 32-bit userspace (armeabi-v7a); x86_64 only for the emulator
        // (debug build or -Pemulator), otherwise every APK would be needlessly large.
        ndk {
            abiFilters += "armeabi-v7a"
            if (project.hasProperty("emulator")) abiFilters += "x86_64"
        }
        // The built-in test account exists only in the debug build; release always signs in via the settings.
        buildConfigField("String", "SYNC_ENDPOINT", "\"\"")
        buildConfigField("String", "SYNC_USER", "\"\"")
        buildConfigField("String", "SYNC_PASSWORD", "\"\"")
    }

    buildTypes {
        debug {
            ndk { abiFilters += "x86_64" }
            buildConfigField("String", "SYNC_ENDPOINT", "\"${local.getProperty("sync.endpoint", "")}\"")
            buildConfigField("String", "SYNC_USER", "\"${local.getProperty("sync.user", "")}\"")
            buildConfigField("String", "SYNC_PASSWORD", "\"${local.getProperty("sync.password", "")}\"")
        }
        release {
            // R8 removes unused code and optimizes; on the weak watch the biggest lever for startup and jank.
            // Compose and Wear ship their baseline profiles, profileinstaller picks them up.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key for now: this way the version on the watch can be updated without
            // reinstalling (and without losing sign-in and collection).
            // A release needs its own key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Generate the language list for the system's per-app language picker (Android 13+) from the values-* folders.
    androidResources {
        generateLocaleConfig = true
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
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0")

    // Pure JVM tests for logic without Android (deck list, numbers, text processing).
    testImplementation("junit:junit:4.13.2")
}
