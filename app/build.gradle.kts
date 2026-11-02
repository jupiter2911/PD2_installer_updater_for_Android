plugins {
    id("com.android.application")
}

android {
    namespace = "com.pd2updater"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pd2updater"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload APK: signed with the debug key so it installs without a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The real org.json for JVM tests (android.jar has only stubs).
    testImplementation("org.json:json:20240303")
}
