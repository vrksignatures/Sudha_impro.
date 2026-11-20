plugins {
    id("com.android.application")
}

android {
    namespace = "com.vrksignatures.sudhaautobot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vrksignatures.sudhaautobot"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            // Debug signing keeps `assembleRelease` usable without publishing a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        // Lint is not a build gate for a single-module utility app.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Intentionally zero third-party dependencies: framework APIs only.
}
