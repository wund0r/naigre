plugins {
    id("com.android.application")
}

android {
    namespace = "wund0r.naigre.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "wund0r.naigre.reader"
        minSdk = 26
        targetSdk = 36
        versionCode = 52
        versionName = "0.29.4"
    }

    buildTypes {
        release {
            // Keep the prototype release path predictable. R8/resource shrinking can be enabled
            // later as a separate, measured change after MuPDF and Markwon rules are validated.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation("com.artifex.mupdf:fitz:1.28.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
}
