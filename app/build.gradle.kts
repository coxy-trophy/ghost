plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.coxy.ghost"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.coxy.ghost"

        minSdk = 33
        targetSdk = 36

        versionCode = 20
        versionName = "0.10.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
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
