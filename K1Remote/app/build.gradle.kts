plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.jlambert.k1remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.jlambert.k1remote"
        minSdk = 24
        targetSdk = 35
        versionCode = 32
        versionName = "0.23"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Aucune dépendance externe : la liaison USB CDC-ACM est codée directement
// avec android.hardware.usb.
