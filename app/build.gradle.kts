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
        versionCode = 60
        versionName = "0.35.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Personal prototype installs continue to use the production application ID.
        }
        create("verification") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".verification"
            matchingFallbacks += listOf("debug")
        }
        release {
            // Keep the prototype release path predictable. R8/resource shrinking can be enabled
            // later as a separate, measured change after MuPDF and Markwon rules are validated.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    // Instrumentation installs a disposable target package and can never uninstall user data.
    testBuildType = "verification"

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
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
