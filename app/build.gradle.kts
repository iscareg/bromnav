import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.bromnav"
    compileSdk = 35
    defaultConfig {
        applicationId = "nl.bromnav"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
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
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    // Release APKs carry the offline maps (data/ is not in git: tools/update_data.sh fetches it)
    sourceSets.getByName("release").assets.srcDir(layout.buildDirectory.dir("mapassets"))
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
}

val copyMapData = tasks.register<Copy>("copyMapData") {
    from(rootProject.file("data/segments")) { include("*.rd5") }
    from(rootProject.file("data/tiles")) { include("groningen.pmtiles") }
    into(layout.buildDirectory.dir("mapassets/mapdata"))
}
tasks.matching { it.name == "mergeReleaseAssets" }.configureEach { dependsOn(copyMapData) }
