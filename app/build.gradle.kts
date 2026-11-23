plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.jlambert.k1remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.jlambert.k1remote.essai"   // appli d'essai, installable à côté de K1 Remote (0.63)
        minSdk = 24
        targetSdk = 35
        versionCode = 92
        versionName = "0.83-essai"
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
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric télécharge ses images Android depuis le même miroir
                it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
            }
        }
    }
}

// Tests seulement (rien n'est ajouté à l'appli) : captures d'écran de référence sous Robolectric.
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// Aucune dépendance externe : la liaison USB CDC-ACM est codée directement
// avec android.hardware.usb.
