// Explicit import: inside a Kotlin build script `java` resolves to Gradle's java extension, so
// `java.util.Properties` does not.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.ksp)
}

/**
 * The Ollama Cloud API key, baked into the build from a gitignored file.
 *
 * GHOST is a single-user app on Coxy's own device and the key was being lost on every priv-app
 * deploy — `/data/data/com.coxy.ghost` is reset by some deploys (GHOST.md §8, cause still unknown),
 * taking `shared_prefs/` with it. Re-entering the key by hand after each deploy was the only way
 * back, and forgetting to meant GHOST silently answered from the local 1.5B instead of the cloud.
 * Baking it into the APK makes the cloud brain survive a wipe with no manual step.
 *
 * `keys.properties` is gitignored. The key is still readable by anyone holding the APK — a
 * `buildConfigField` is a string constant in the dex, not a secret — which is an accepted trade
 * for a personal build that is never distributed. A key entered in the UI still wins over this one,
 * so rotating without a rebuild works.
 */
val keysFile = rootProject.file("keys.properties")
val ollamaApiKey: String = if (keysFile.exists()) {
    Properties().apply { keysFile.inputStream().use { load(it) } }
        .getProperty("OLLAMA_API_KEY", "")
        .trim()
} else {
    logger.warn("keys.properties not found — GHOST will build without a baked-in Ollama key.")
    ""
}

android {
    namespace = "com.coxy.ghost"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.coxy.ghost"

        minSdk = 33
        targetSdk = 36

        versionCode = 28
        versionName = "0.11.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "OLLAMA_API_KEY", "\"$ollamaApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Room writes the schema of each version here. Committed, so a future migration can be diffed and
// tested against the version it migrates from. Note the v1 and v2 schemas do not exist and cannot
// be reconstructed — export was off when they shipped — so only v3 onward is covered.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.bundles.androidx)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.play.services.location)

    // Phase 6 memory: Room (persistent turn store) + TFLite (on-device sentence embeddings).
    // Room's compiler runs via KSP (the ksp plugin is applied above). TFLite is a Java/JNI lib
    // with its own runtime — :lib (the llama.cpp native build) is NOT touched.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.tensorflow.lite)

    // Phase 8 cloud: secure storage for the Ollama API key.
    implementation(libs.androidx.security.crypto)

    implementation(project(":lib"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
