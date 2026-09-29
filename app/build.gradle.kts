plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.offlinenarrator.benchmark"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.offlinenarrator.benchmark"
        minSdk = 24
        targetSdk = 36

        // Phase-0 target device profile: modern ARM64 Android only.
        // This strips unused ONNX Runtime ABIs from the benchmark APK.
        ndk {
            abiFilters += "arm64-v8a"
        }
        versionCode = 24
        versionName = "0.8.0-reader-precision-player"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/INDEX.LIST"
        )
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Keep the whole Lifecycle family on the API-36-compatible generation.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Phase-0 prototype adapter only. The production engine remains replaceable.
    // Phase-0 Kokoro AAR is built from upstream source by GitHub Actions.
    implementation(files("libs/kokoro-android.aar"))
    // A local AAR does not carry Maven transitive dependencies, so declare them here.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
}
