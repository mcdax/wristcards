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
        // Galaxy Watch Ultra 2 läuft mit 32-Bit-Userspace (armeabi-v7a); x86_64 nur für den Emulator
        // (Debug-Build oder -Pemulator), sonst wäre jede APK unnötig groß.
        ndk {
            abiFilters += "armeabi-v7a"
            if (project.hasProperty("emulator")) abiFilters += "x86_64"
        }
        // Eingebautes Testkonto gibt es nur im Debug-Build; Release meldet sich immer über die Einstellungen an.
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
            // R8 entfernt Ungenutztes und optimiert; auf der schwachen Uhr der größte Hebel für Start und Ruckeln.
            // Compose und Wear bringen ihre Baseline-Profile mit, der profileinstaller übernimmt sie.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Vorerst mit dem Debug-Schlüssel signiert: So lässt sich die Version auf der Uhr ohne
            // Neuinstallation (und ohne Verlust von Anmeldung und Sammlung) aktualisieren.
            // Für eine Veröffentlichung braucht es einen eigenen Schlüssel.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Sprachliste für die App-Sprachauswahl des Systems (Android 13+) aus den values-*-Ordnern erzeugen.
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

    // Reine JVM-Tests für Logik ohne Android (Deckliste, Zahlen, Textaufbereitung).
    testImplementation("junit:junit:4.13.2")
}
