plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jimtime.wear"
    compileSdk = 35

    defaultConfig {
        // Must match the phone app's applicationId
        // (`jimtime-frontend/android/app/build.gradle`) so that Google
        // Play recognises this APK as the Wear companion of the JimTime
        // phone listing and offers an automatic install on the paired
        // watch when the user installs the phone app.
        //
        // Note: `namespace` above stays `com.jimtime.wear` — it scopes
        // Kotlin packages / the generated R class and is independent of
        // the Play Store identifier. Changing it would force renaming
        // every source directory.
        applicationId = "com.jimtime.gabrielegusmeroli"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.play.services.wearable)
    implementation(libs.play.services.location)
    implementation(libs.wear.ongoing)
    implementation(libs.androidx.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.navigation)
    implementation(libs.compose.ui.tooling)
    implementation(libs.wear.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    // F5b (Nuoto, Wear OS) — ExerciseClient: unica sorgente HR/distanza/
    // vasche/bracciate per swim_pool/swim_open_water (GPS+SensorManager,
    // usati da tutte le altre attività, non funzionano/non bastano in acqua).
    implementation(libs.health.services.client)
    // health-services-client dichiara guava/concurrent-futures-ktx come
    // dipendenze "runtime" nel suo POM (non "api"): il tipo ListenableFuture
    // che le sue API pubbliche restituiscono (startExerciseAsync, ecc.) non
    // è altrimenti risolvibile a compile-time da questo modulo. Versioni
    // allineate a quelle che health-services-client:1.1.0 già porta.
    implementation("com.google.guava:guava:32.0.1-android")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.1.0")
    testImplementation(libs.junit)
    // Pure-JVM org.json for unit tests (the Android SDK's org.json stub
    // used at compile time throws at runtime off-device) — verifies the
    // swim wire payload round-trips through real JSON, not just a Map.
    testImplementation("org.json:json:20231013")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}
