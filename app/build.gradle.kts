plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "ar.com.hst.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ar.com.hst.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 15
        versionName = "1.5.7"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../hst-release.jks")
            storePassword = "hstapp2026"
            keyAlias = "hstapp"
            keyPassword = "hstapp2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
        viewBinding = true
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.0.0")

    // Room (offline DB)
    implementation("androidx.room:room-runtime:2.5.2")
    implementation("androidx.room:room-ktx:2.5.2")
    kapt("androidx.room:room-compiler:2.5.2")

    // CameraX (QR scanning)
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // ML Kit Barcode Scanning
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Retrofit (API calls)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // WorkManager (background sync)
    implementation("androidx.work:work-runtime-ktx:2.8.1")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
}
