import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release APKs carry the offline maps (data/ is not in git: tools/update_data.sh fetches it)
val copyMapData = tasks.register<Sync>("copyMapData") {
    from(rootProject.file("data/segments")) { include("*.rd5"); into("mapdata") }
    from(rootProject.file("data/tiles")) { include("groningen.pmtiles"); into("mapdata") }
    into(layout.buildDirectory.dir("mapassets"))
}

// Place search index + offline address book (all builds; ~0.5 + 1.2 MB):
// data/pois.tsv.gz (tools/build_pois.py), data/addresses.tsv.gz (tools/build_addresses.py)
val copyPoiData = tasks.register<Sync>("copyPoiData") {
    from(rootProject.file("data")) { include("pois.tsv.gz", "addresses.tsv.gz") }
    into(layout.buildDirectory.dir("poiassets"))
}

android {
    namespace = "nl.bromnav"
    compileSdk = 35
    defaultConfig {
        applicationId = "nl.bromnav"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.6.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // Release signing key lives outside the repo (~/.bromnav-release/signing.properties).
    val signingProps = File(System.getProperty("user.home"), ".bromnav-release/signing.properties")
    signingConfigs {
        if (signingProps.exists()) create("release") {
            val p = Properties().apply { signingProps.inputStream().use { load(it) } }
            storeFile = File(p.getProperty("storeFile")); storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias"); keyPassword = p.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug {
            // remote control (tools/dbg.sh) and debug menu entries
            buildConfigField("boolean", "DEBUG_TOOLS", "true")
            // ./gradlew assembleDebug -Pbench : not debuggable, so it runs as fast as a release
            // (for timing on the phone) but keeps the debug key and the remote control
            if (project.hasProperty("bench")) isDebuggable = false
        }
        release {
            buildConfigField("boolean", "DEBUG_TOOLS", "false")
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    sourceSets.getByName("release").assets.srcDir(layout.buildDirectory.dir("mapassets"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("poiassets"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += listOf("dat", "brf", "rd5", "pmtiles") }
    buildFeatures { buildConfig = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(files("libs/brouter-1.7.10-ro.jar"))
    implementation("org.maplibre.gl:android-sdk:12.3.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")   // bundled Latin model, offline
    implementation("com.google.mlkit:translate:17.0.3")          // NL->EN, model downloaded once (~30 MB), then offline
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// every task that reads assets (merge, lint) must run after the copies
tasks.matching { Regex("merge\\w*Assets|generate\\w*Lint\\w*Model|lintVital\\w*|lintAnalyze\\w*|lint\\w*Report\\w*").matches(it.name) }.configureEach {
    dependsOn(copyPoiData)
    if (name.contains("Release")) dependsOn(copyMapData)
}

