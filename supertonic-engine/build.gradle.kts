plugins {
    id("com.android.application")
}

android {
    namespace = "com.offlinenarrator.supertonicengine"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.offlinenarrator.supertonicengine"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-supertonic3-litert"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("audio.soniqo:speech:0.0.22")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
